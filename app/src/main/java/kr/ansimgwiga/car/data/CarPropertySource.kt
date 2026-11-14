package kr.ansimgwiga.car.data

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import kr.ansimgwiga.car.core.TripSignal
import kr.ansimgwiga.car.diag.DiagGroup
import kr.ansimgwiga.car.diag.DiagStatus
import kr.ansimgwiga.car.diag.DiagStore
import java.util.Locale

/**
 * 안드로이드 표준 차량 API → 속도·기어·주행 제한 신호.
 * Vehicle SDK에는 속도·기어가 없어서(기어 도메인은 숨김 처리) 여기서 읽는다.
 *  - 속도 PERF_VEHICLE_SPEED: 권한 CAR_SPEED(실행 중 허용 필요)
 *  - 기어 GEAR_SELECTION: 권한 CAR_POWERTRAIN(설치 시 허용)
 *  - 주행 제한 CarUxRestrictions: 권한 없음. 기어·속도가 둘 다 안 될 때 주행 판단에 쓴다.
 */
@Suppress("DEPRECATION")
class CarPropertySource(
    private val context: Context,
    private val emit: (TripSignal) -> Unit,
    private val diag: DiagStore
) {
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var ux: CarUxRestrictionsManager? = null
    private var speedSubscribed = false
    @Volatile private var lastSpeedEmitAt = 0L

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = onValue(value.propertyId, value.value)
        override fun onErrorEvent(propId: Int, zone: Int) =
            diag.put(DiagGroup.CAR_API, "err/$propId", "오류 · ${name(propId)}", "zone $zone", DiagStatus.FAIL)
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r -> onUx(r) }

    fun start() {
        if (car != null) return
        val c = try {
            Car.createCar(context.applicationContext)
        } catch (t: Throwable) {
            diag.fail(DiagGroup.CAR_API, "car", "Car 연결", t)
            return
        }
        if (c == null) {
            diag.put(DiagGroup.CAR_API, "car", "Car 연결", "createCar가 null", DiagStatus.FAIL)
            return
        }
        car = c
        diag.put(DiagGroup.CAR_API, "car", "Car 연결", "연결됨", DiagStatus.OK)

        val pm = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull()
        properties = pm
        if (pm == null) {
            diag.put(DiagGroup.CAR_API, "pm", "CarPropertyManager", "못 얻음", DiagStatus.FAIL)
        } else {
            subscribe(pm, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            runCatching { pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }
                .onSuccess { onValue(VehiclePropertyIds.GEAR_SELECTION, it) }
                .onFailure { diag.fail(DiagGroup.CAR_API, "gear", "기어", it) }
            subscribeSpeed()
        }

        val uxm = runCatching { c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }.getOrNull()
        ux = uxm
        if (uxm == null) {
            diag.put(DiagGroup.CAR_API, "ux", "주행 제한 상태", "못 얻음", DiagStatus.WARN)
        } else {
            runCatching {
                uxm.registerListener(uxListener)
                onUx(uxm.currentCarUxRestrictions)
            }.onFailure { diag.fail(DiagGroup.CAR_API, "ux", "주행 제한 상태", it) }
        }
    }

    /** 속도 권한을 허용받은 뒤에도 부른다. */
    fun subscribeSpeed() {
        val pm = properties ?: return
        val granted = context.checkSelfPermission(PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED
        diag.put(
            DiagGroup.CAR_API, "perm", "속도 권한(CAR_SPEED)",
            if (granted) "허용됨" else "아직 허용 안 됨 → 진단 화면에서 '권한 요청'",
            if (granted) DiagStatus.OK else DiagStatus.WARN
        )
        if (!granted || speedSubscribed) return
        // 1초에 한 번이면 급제동(1초에 10 km/h 이상 감속) 판단에 충분하고, 짧은 구간의 잡음도 피한다.
        speedSubscribed = subscribe(pm, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        runCatching { pm.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0) }
            .onSuccess { onValue(VehiclePropertyIds.PERF_VEHICLE_SPEED, it) }
            .onFailure { diag.fail(DiagGroup.CAR_API, "speed", "속도", it) }
    }

    fun release() {
        runCatching { properties?.unregisterCallback(callback) }
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        car = null
        properties = null
        ux = null
        speedSubscribed = false
    }

    // ------------------------------------------------------------------

    private fun subscribe(pm: CarPropertyManager, propId: Int, rate: Float): Boolean {
        val ok = runCatching { pm.registerCallback(callback, propId, rate) }
            .onFailure { diag.fail(DiagGroup.CAR_API, "sub/$propId", "구독 · ${name(propId)}", it) }
            .getOrDefault(false)
        diag.put(
            DiagGroup.CAR_API, "sub/$propId", "구독 · ${name(propId)}",
            if (ok) "성공" else "실패(지원 안 함 또는 권한 없음)", if (ok) DiagStatus.OK else DiagStatus.FAIL
        )
        return ok
    }

    private fun onValue(propId: Int, value: Any?) {
        when (propId) {
            VehiclePropertyIds.GEAR_SELECTION -> {
                val gear = (value as? Int) ?: return
                val parked = gear == GEAR_PARK
                diag.put(DiagGroup.CAR_API, "gear", "기어", "${gearName(gear)} ($gear)", DiagStatus.OK)
                emit(TripSignal.Gear(parked))
            }
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                val ms = (value as? Float) ?: return
                val kmh = ms * 3.6f
                diag.put(DiagGroup.CAR_API, "speed", "속도", String.format(Locale.US, "%.1f km/h", kmh), DiagStatus.OK)
                val now = System.currentTimeMillis()
                if (now - lastSpeedEmitAt < MIN_SPEED_INTERVAL_MS) return
                lastSpeedEmitAt = now
                emit(TripSignal.Speed(kmh))
            }
        }
    }

    private fun onUx(r: CarUxRestrictions?) {
        if (r == null) return
        val restricted = r.isRequiresDistractionOptimization
        diag.put(DiagGroup.CAR_API, "ux", "주행 제한 상태", if (restricted) "주행 중 제한" else "제한 없음(정차)", DiagStatus.OK)
        emit(TripSignal.UxRestricted(restricted))
    }

    private fun name(propId: Int) = when (propId) {
        VehiclePropertyIds.GEAR_SELECTION -> "기어"
        VehiclePropertyIds.PERF_VEHICLE_SPEED -> "속도"
        else -> propId.toString()
    }

    private fun gearName(g: Int) = when (g) {
        GEAR_PARK -> "P"
        GEAR_REVERSE -> "R"
        GEAR_NEUTRAL -> "N"
        GEAR_DRIVE -> "D"
        else -> "기타"
    }

    companion object {
        const val PERMISSION_SPEED = "android.car.permission.CAR_SPEED"

        // android.car.VehicleGear 값(VHAL 표준)
        private const val GEAR_NEUTRAL = 0x0001
        private const val GEAR_REVERSE = 0x0002
        private const val GEAR_PARK = 0x0004
        private const val GEAR_DRIVE = 0x0008
        private const val MIN_SPEED_INTERVAL_MS = 900L
    }
}
