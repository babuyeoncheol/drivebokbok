package kr.ansimgwiga.car.data

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import kr.ansimgwiga.car.BuildConfig
import kr.ansimgwiga.car.core.Maintenance
import kr.ansimgwiga.car.core.SdkValues
import kr.ansimgwiga.car.core.SeatPos
import kr.ansimgwiga.car.core.TripSignal
import kr.ansimgwiga.car.diag.DiagGroup
import kr.ansimgwiga.car.diag.DiagStatus
import kr.ansimgwiga.car.diag.DiagStore
import java.util.concurrent.ConcurrentHashMap
import ai.pleos.playground.vehicle.api.Brake as SdkBrake
import ai.pleos.playground.vehicle.api.Door as SdkDoor
import ai.pleos.playground.vehicle.api.EvBattery as SdkEvBattery
import ai.pleos.playground.vehicle.api.Fuel as SdkFuel
import ai.pleos.playground.vehicle.api.Light as SdkLight
import ai.pleos.playground.vehicle.api.Odometer as SdkOdometer
import ai.pleos.playground.vehicle.api.Safety as SdkSafety
import ai.pleos.playground.vehicle.api.Seat as SdkSeat
import ai.pleos.playground.vehicle.api.Window as SdkWindow
import ai.pleos.playground.vehicle.api.Wiper as SdkWiper

/**
 * Vehicle SDK → 우리 신호.
 *
 * - 문: 공식 샘플과 같은 방식(타입을 직접 써서) 부른다.
 * - 나머지(벨트, 에어백, 정비 경고, 브레이크, 연료, 조명, 와이퍼, 주행 거리): 이름으로 찾아 부른다([SdkCalls]).
 * - 읽은 값은 전부 진단 화면에 원래 모양 그대로 남긴다. 신호로 못 바꾼 값도 거기서 보인다.
 */
class PleosVehicleSource(
    context: Context,
    private val emit: (TripSignal) -> Unit,
    private val diag: DiagStore
) {
    private val vehicle = Vehicle(context.applicationContext)
    private var started = false
    private val calls = SdkCalls { where, error -> report("err/$where", "실패 · $where", error) }
    private val doorListeners = mutableListOf<Pair<DoorArea, DoorPositionListener>>()

    private val airbagByArea = ConcurrentHashMap<String, Boolean>()
    @Volatile private var fuelLow = false
    @Volatile private var evLow = false

    // 스위치(운전자가 고른 위치)와 상태(실제로 켜졌나)를 따로 받는다. 실제 차는 둘이 같이 움직이지만
    // 에뮬레이터는 차 설정 화면에서 스위치만 바뀌고 상태는 그대로라, 둘 중 하나라도 켜짐이면 켜짐으로 본다.
    @Volatile private var headState: Boolean? = null
    @Volatile private var headSwitch: Boolean? = null
    @Volatile private var wiperState: Boolean? = null
    @Volatile private var wiperSwitch: Boolean? = null

    /** 등록(변경 알림) 메서드가 없는 값은 POLL_MS마다 다시 읽는다(전조등·와이퍼 스위치). */
    private val pollers = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private val pollTick = object : Runnable {
        override fun run() {
            pollers.forEach { runCatching { it() } }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /**
     * 차에 없는 값(3열 문, 엔진오일 센서 등)은 SDK가 'not supported'로 거절한다. 고장이 아니라서
     * 빨강 대신 노랑으로, 이유를 짧게 적는다.
     */
    private fun report(key: String, label: String, error: Throwable) {
        if (isUnsupported(error)) {
            diag.put(DiagGroup.VEHICLE, key, label, "이 차(에뮬레이터)에는 없는 값 · 정상", DiagStatus.WARN)
        } else {
            diag.fail(DiagGroup.VEHICLE, key, label, error)
        }
    }

    private fun isUnsupported(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it.message?.contains("not supported", ignoreCase = true) == true }

    /**
     * 주행 거리 값의 단위. 진단 화면에서 값을 보고 정한다(km면 1.0, m면 0.001).
     * 정하기 전에는 엔진에 넣지 않고, 운행 거리는 내비 위치로 계산한다.
     */
    private val odometerToKm: Double? = null

    private data class Feature(
        val id: String,
        val label: String,
        val domains: List<String>,
        val match: (String) -> Boolean,
        /** 부를 영역만 고른다. 차에 없는 3열 좌석, 옆 창문 와이퍼 같은 건 빼서 진단 화면을 깨끗하게. */
        val areas: (String) -> Boolean = { true },
        val onValue: (area: String?, value: Any?) -> Unit
    )

    private fun String.has(word: String) = contains(word, ignoreCase = true)

    private val features: List<Feature> by lazy {
        listOf(
            Feature("belt_warning", "벨트 미착용 경고", listOf("Safety", "Seat"), { it.has("NotBuckled") }, SEAT_AREAS::contains) { area, v ->
                SdkValues.asBool(v)?.let { emit(TripSignal.BeltWarning(SdkValues.seatPos(area) ?: SeatPos.DRIVER, it)) }
            },
            Feature("seat_buckled", "좌석 벨트 체결", listOf("Safety", "Seat"), { it.has("Buckled") && !it.has("Not") }, SEAT_AREAS::contains) { area, v ->
                val seat = SdkValues.seatPos(area)
                val on = SdkValues.asBool(v)
                if (seat != null && on != null) emit(TripSignal.SeatBuckled(seat, on))
            },
            Feature("seat_occupied", "좌석 착석", listOf("Seat"), { it.has("SeatOccupancy") }, SEAT_AREAS::contains) { area, v ->
                val seat = SdkValues.seatPos(area)
                val on = SdkValues.asBool(v)
                if (seat != null && on != null) emit(TripSignal.SeatOccupied(seat, on))
            },
            Feature("airbag", "에어백 전개", listOf("Safety"), { it.has("Airbag") && it.has("Deploy") }, SEAT_AREAS::contains) { area, v ->
                SdkValues.asBool(v)?.let {
                    airbagByArea[area ?: "ALL"] = it
                    emit(TripSignal.Airbag(airbagByArea.values.any { deployed -> deployed }))
                }
            },
            maintenance("engine_oil", "엔진오일", Maintenance.ENGINE_OIL) { it.has("EngineOil") },
            maintenance("brake_fluid", "브레이크액", Maintenance.BRAKE_FLUID) { it.has("BrakeFluid") },
            maintenance("washer", "워셔액", Maintenance.WASHER_FLUID) { it.has("Washer") },
            maintenance("power_down", "파워다운", Maintenance.POWER_DOWN) { it.has("PowerDown") },
            maintenance("transmission_oil", "변속기오일", Maintenance.TRANSMISSION_OIL) { it.has("TransmissionOil") },
            Feature("brake_pedal", "브레이크 페달", listOf("Brake"), { it.has("Pedal") }) { _, v ->
                SdkValues.asBool(v)?.let { emit(TripSignal.Brake(it)) }
            },
            // SDK에 '부족' 경고 메서드는 없다. 잔량(%)을 받아 LOW_ENERGY_PERCENT 이하면 부족으로 본다.
            // (getEvBatteryLevel은 Wh 단위 에너지라 쓰지 않는다. 에뮬레이터 값 77400.0)
            Feature("fuel_percent", "연료 잔량(%)", listOf("Fuel"), { it.has("FuelPercent") }) { _, v ->
                lowFromPercent(v)?.let { fuelLow = it; emit(TripSignal.EnergyLow(fuelLow || evLow)) }
            },
            Feature("ev_soc", "배터리 잔량(%)", listOf("EvBattery"), { it.has("StateOfCharge") }) { _, v ->
                lowFromPercent(v)?.let { evLow = it; emit(TripSignal.EnergyLow(fuelLow || evLow)) }
            },
            Feature("odometer", "주행 거리", listOf("Odometer"), { it.has("Distance") || it.has("Odometer") || it.has("Mileage") }) { _, v ->
                val scale = odometerToKm
                val raw = SdkValues.asDouble(v)
                if (scale != null && raw != null) emit(TripSignal.Odometer(raw * scale))
            },
            Feature("headlight", "전조등", listOf("Light"), { it.has("LowBeam") && it.has("State") }) { _, v ->
                SdkValues.asBool(v)?.let { headState = it; emitHeadlights() }
            },
            // LightSwitch: OFF·ON·DAYTIME_RUNNING·AUTOMATIC. 자동은 켜졌는지 모르니 상태 값에 맡긴다.
            Feature("headlight_switch", "전조등 스위치", listOf("Light"), { it.has("LowBeamLightSwitch") }) { _, v ->
                headSwitch = switchOn(v, SdkValues::asBool)
                emitHeadlights()
            },
            Feature("fog", "안개등", listOf("Light"), { it.has("FrontFog") && it.has("State") }) { _, v ->
                SdkValues.asBool(v)?.let { emit(TripSignal.Lights(fog = it)) }
            },
            Feature("wiper", "와이퍼", listOf("Wiper", "Window"), { it.has("WiperState") }, { it == "FRONT_WINDSHIELD" }) { _, v ->
                SdkValues.wiperOn(v)?.let { wiperState = it; emitWiper() }
            },
            // WiperSwitch: OFF·MIST·INTERMITTENT_LEVEL_n·CONTINUOUS_LEVEL_n·AUTO. AUTO는 비가 와야 도니 상태 값에 맡긴다.
            Feature("wiper_switch", "와이퍼 스위치", listOf("Wiper"), { it.has("WiperSwitch") }, { it == "FRONT_WINDSHIELD" }) { _, v ->
                wiperSwitch = switchOn(v, SdkValues::wiperOn)
                emitWiper()
            }
        )
    }

    private fun switchOn(v: Any?, read: (Any?) -> Boolean?): Boolean? {
        val name = (v as? Enum<*>)?.name ?: v?.toString()
        return if (name != null && name.contains("AUTO", ignoreCase = true)) null else read(v)
    }

    private fun emitHeadlights() {
        if (headState == null && headSwitch == null) return
        emit(TripSignal.Lights(headlights = headState == true || headSwitch == true))
    }

    private fun emitWiper() {
        if (wiperState == null && wiperSwitch == null) return
        emit(TripSignal.Wiper(wiperState == true || wiperSwitch == true))
    }

    private fun lowFromPercent(v: Any?): Boolean? =
        SdkValues.asDouble(v)?.takeIf { it in 0.0..100.0 }?.let { it <= LOW_ENERGY_PERCENT }

    private fun maintenance(id: String, label: String, kind: Maintenance, match: (String) -> Boolean) =
        Feature(id, "$label 경고", listOf("Safety"), match) { _, v ->
            SdkValues.asBool(v)?.let { emit(TripSignal.MaintenanceWarning(kind, it)) }
        }

    fun start() {
        if (started) return
        try {
            vehicle.initialize()
            started = true
            diag.put(DiagGroup.VEHICLE, "init", "SDK 초기화", "성공 · Vehicle SDK ${BuildConfig.VEHICLE_SDK_VERSION}", DiagStatus.OK)
        } catch (e: Exception) {
            diag.fail(DiagGroup.VEHICLE, "init", "SDK 초기화", e)
            return
        }
        val domains = buildDomains()
        domains.values.forEach { d ->
            diag.put(DiagGroup.SDK_METHODS, "domain/${d.name}", d.name, d.methodNames().joinToString(", ").ifEmpty { "(목록 없음)" })
        }
        startDoors()
        features.forEach { startFeature(it, domains) }
        if (pollers.isNotEmpty()) handler.postDelayed(pollTick, POLL_MS)
    }

    /** 현재 값을 한 번 더 읽는다(진단 화면 '다시 읽기'). */
    fun refresh() {
        if (!started) return start()
        val domains = buildDomains()
        readDoors()
        features.forEach { f -> forEachMethod(f, domains, register = false) }
    }

    fun release() {
        if (!started) return
        val door = runCatching { vehicle.getDoor() }.getOrNull()
        doorListeners.forEach { (area, listener) -> runCatching { door?.unregisterDoorPosition(area, listener) } }
        doorListeners.clear()
        handler.removeCallbacks(pollTick)
        pollers.clear()
        calls.release()
        runCatching { vehicle.release() }
        started = false
    }

    // ------------------------------------------------------------------

    private fun buildDomains(): Map<String, SdkDomain> {
        val out = LinkedHashMap<String, SdkDomain>()
        fun add(name: String, make: () -> SdkDomain) {
            try {
                out[name] = make()
            } catch (t: Throwable) {
                diag.fail(DiagGroup.SDK_METHODS, "domain/$name", name, t)
            }
        }
        add("Safety") { SdkDomain("Safety", SdkSafety::class, vehicle.getSafety()) }
        add("Seat") { SdkDomain("Seat", SdkSeat::class, vehicle.getSeat()) }
        add("Door") { SdkDomain("Door", SdkDoor::class, vehicle.getDoor()) }
        add("Brake") { SdkDomain("Brake", SdkBrake::class, vehicle.getBrake()) }
        add("Fuel") { SdkDomain("Fuel", SdkFuel::class, vehicle.getFuel()) }
        add("EvBattery") { SdkDomain("EvBattery", SdkEvBattery::class, vehicle.getEvBattery()) }
        add("Light") { SdkDomain("Light", SdkLight::class, vehicle.getLight()) }
        add("Wiper") { SdkDomain("Wiper", SdkWiper::class, vehicle.getWiper()) }
        add("Window") { SdkDomain("Window", SdkWindow::class, vehicle.getWindow()) }
        add("Odometer") { SdkDomain("Odometer", SdkOdometer::class, vehicle.getOdometer()) }
        return out
    }

    private fun startFeature(feature: Feature, domains: Map<String, SdkDomain>) {
        val found = forEachMethod(feature, domains, register = true)
        if (!found) {
            diag.put(DiagGroup.SDK_METHODS, "use/${feature.id}", feature.label, "맞는 메서드를 못 찾음", DiagStatus.WARN)
        }
    }

    /** 기능에 맞는 읽기·등록 메서드를 찾아 영역마다 부른다. 찾았으면 true. */
    private fun forEachMethod(feature: Feature, domains: Map<String, SdkDomain>, register: Boolean): Boolean {
        for (name in feature.domains) {
            val domain = domains[name] ?: continue
            val read = domain.find(READ_PREFIXES, feature.match)
            val reg = domain.find(listOf("register"), feature.match)
            if (read == null && reg == null) continue

            if (register) {
                diag.put(
                    DiagGroup.SDK_METHODS, "use/${feature.id}", feature.label,
                    "$name · " + listOfNotNull(read?.name, reg?.name).joinToString(" / "), DiagStatus.OK
                )
            }
            read?.let { fn ->
                for (area in areasFor(feature, fn)) {
                    calls.read(domain, fn, area) { v -> onValue(feature, area, v) }
                }
            }
            if (register) reg?.let { fn ->
                for (area in areasFor(feature, fn)) {
                    calls.register(domain, fn, area) { v -> onValue(feature, area, v) }
                }
            }
            if (register && reg == null && read != null) {
                val areas = areasFor(feature, read)
                pollers += { areas.forEach { area -> calls.read(domain, read, area) { v -> onValue(feature, area, v) } } }
            }
            return true
        }
        return false
    }

    private fun areasFor(feature: Feature, fn: kotlin.reflect.KFunction<*>): List<String?> =
        calls.areaNames(fn)?.filter(feature.areas) ?: listOf(null)

    private fun onValue(feature: Feature, area: String?, value: Any?) {
        val label = feature.label + (area?.let { " · $it" } ?: "")
        diag.put(DiagGroup.VEHICLE, "${feature.id}/${area ?: "-"}", label, SdkValues.describe(value), DiagStatus.OK)
        try {
            feature.onValue(area, value)
        } catch (t: Throwable) {
            diag.fail(DiagGroup.VEHICLE, "map/${feature.id}", "변환 실패 · ${feature.label}", t)
        }
    }

    // ---------------- 문(공식 샘플과 같은 타입 호출) ----------------

    private fun startDoors() {
        val door = vehicle.getDoor()
        for (value in DOORS) {
            val area = DoorArea(value)
            readDoor(door, value, area)
            val listener = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) = onDoor(value.name, pos)
                override fun onFailed(e: Exception) = report("door/${value.name}", "문 ${value.name}", e)
            }
            try {
                door.registerDoorPosition(area, listener)
                doorListeners += area to listener
            } catch (e: Exception) {
                report("door-reg/${value.name}", "문 등록 ${value.name}", e)
            }
        }
    }

    private fun readDoors() {
        val door = vehicle.getDoor()
        DOORS.forEach { readDoor(door, it, DoorArea(it)) }
    }

    private fun readDoor(door: SdkDoor, value: DoorValue, area: DoorArea) {
        try {
            door.getCurrentDoorPosition(
                area = area,
                onComplete = { pos: Int? -> onDoor(value.name, pos) },
                onFailed = { e -> report("door/${value.name}", "문 ${value.name}", e) }
            )
        } catch (e: Exception) {
            report("door/${value.name}", "문 ${value.name}", e)
        }
    }

    private fun onDoor(areaName: String, position: Int?) {
        val pos = SdkValues.doorPos(areaName)
        val open = position?.let { it > 0 }
        val label = "문 $areaName" + (pos?.let { " · ${it.label}" } ?: "")
        val text = when (open) { null -> "null"; true -> "$position (열림)"; false -> "$position (닫힘)" }
        diag.put(DiagGroup.VEHICLE, "door/$areaName", label, text, DiagStatus.OK)
        if (pos != null && open != null) emit(TripSignal.Door(pos, open))
    }

    private companion object {
        val READ_PREFIXES = listOf("getCurrent", "get", "is", "has")

        /** 1열 가운데·3열은 승용 5인승에 없다. */
        val SEAT_AREAS = setOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")

        /** 3열 문은 승용 5인승에 없다. */
        val DOORS = DoorValue.entries.filterNot { it.name.startsWith("ROW_3") }

        const val POLL_MS = 3_000L

        /** 연료·배터리가 이 % 이하이면 '부족'. */
        const val LOW_ENERGY_PERCENT = 15.0
    }
}
