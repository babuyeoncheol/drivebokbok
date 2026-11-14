package kr.ansimgwiga.car.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 운전복복의 판단 로직 전부.
 *
 * - 신호를 [onSignal]로 넣고, 1초마다 [tick]을 부르면 된다.
 * - 돌려받은 [TripEvent] 목록을 차(안내음)와 서버(가족 알림)로 보낸다.
 * - 시간은 [clock]으로만 읽는다. 테스트와 시연(시간 빨리 감기)에서 시계를 바꿔 끼운다.
 *
 * 1차 기능: 출발 전 체크, 안심 귀가(출발·위치·도착), 휴식 안내, 긴급 알림(에어백), 뒷좌석 확인.
 * 2차 기능(설정으로 켬): 급제동 케어, 야간·비 맥락.
 */
class TripEngine(
    private val config: EngineConfig = EngineConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    // ---- 마지막으로 받은 신호 ----
    private var gearKnown = false
    private var gearParked = true
    private var speedKnown = false
    private var speedKmh = 0f
    private var lastSpeedAt: Long? = null
    private var lastMovingAt: Long? = null
    private var uxKnown = false
    private var uxRestricted = false

    private val doorOpen = mutableMapOf<DoorPos, Boolean>()
    private val beltWarnings = linkedSetOf<SeatPos>()
    /** 차가 벨트 경고 값을 한 번이라도 보냈나. 안 보내는 차(Pleos 에뮬레이터 등)는 벨트 체결 값으로 대신한다. */
    private var beltWarningSource = false
    private val buckled = mutableMapOf<SeatPos, Boolean>()
    private val occupied = mutableMapOf<SeatPos, Boolean>()
    private var airbag: Boolean? = null
    private val maintenance = linkedSetOf<Maintenance>()
    private var energyLow = false
    private var brakePressed: Boolean? = null
    private var headlights = false
    private var fogLights = false
    private var wiperOn = false
    private var location: LatLng? = null
    private var destination: String? = null
    private var naviArrived = false
    private var odometerKm: Double? = null

    // ---- 운행 상태 ----
    private var driving = false
    private var parkedSince: Long? = null
    private var tripActive = false
    private var tripStartAt = 0L
    private var tripStartOdometer: Double? = null
    private var tripPathKm = 0.0
    private var lastPathPoint: LatLng? = null
    private var tripRestCount = 0
    private var tripHardBrakeCount = 0
    private val tripMaintenance = linkedSetOf<Maintenance>()
    private val tripContext = linkedSetOf<String>()

    // 휴식 안내
    private var segmentStart = 0L
    private var continuousAccumMs = 0L
    private var nextRestAtMs = config.restThresholdMs

    // 뒷좌석 확인
    private var manualChild: Boolean? = null
    private var childInRear = false
    private var rearOpenedAt: Long? = null
    private var rearOpenedThisPark = false
    private var rearCheckDoneThisPark = false
    private var rearCheckDeadline: Long? = null

    // 벨트 · 급제동 · 알림 간격
    private var beltDrivingSince: Long? = null
    private val hardBrakeTimes = ArrayDeque<Long>()
    private var lastHardBrakeAt: Long? = null
    private val lastNoticeAt = mutableMapOf<NoticeKind, Long>()
    private var lastLocationSentAt: Long? = null

    /** 신호 하나를 넣고, 그 결과로 생긴 이벤트를 돌려받는다. */
    @Synchronized
    fun onSignal(signal: TripSignal): List<TripEvent> {
        val now = clock()
        val out = mutableListOf<TripEvent>()
        var openedDoor: DoorPos? = null

        when (signal) {
            is TripSignal.Gear -> { gearKnown = true; gearParked = signal.parked }
            is TripSignal.Speed -> onSpeed(signal.kmh, now, out)
            is TripSignal.UxRestricted -> { uxKnown = true; uxRestricted = signal.restricted }
            is TripSignal.Door -> {
                val wasOpen = doorOpen[signal.pos] == true
                doorOpen[signal.pos] = signal.open
                if (signal.open && !wasOpen) openedDoor = signal.pos
            }
            is TripSignal.BeltWarning -> {
                beltWarningSource = true
                if (signal.active) beltWarnings += signal.seat else beltWarnings -= signal.seat
                updateBeltTimer(now)
            }
            is TripSignal.SeatBuckled -> {
                buckled[signal.seat] = signal.buckled
                if (signal.seat.isRear && signal.buckled && config.childMode && manualChild == null && tripActive) {
                    childInRear = true
                }
                updateBeltTimer(now)
            }
            is TripSignal.SeatOccupied -> {
                occupied[signal.seat] = signal.occupied
                if (signal.seat.isRear && signal.occupied && config.childMode && manualChild == null && tripActive) {
                    childInRear = true
                }
                updateBeltTimer(now)
            }
            is TripSignal.Airbag -> {
                val before = airbag
                airbag = signal.deployed
                // 처음 읽은 값은 기준으로만 쓴다. '안 터짐 → 터짐'으로 바뀔 때만 긴급 알림.
                if (signal.deployed && before == false) emergency(now, out)
            }
            is TripSignal.MaintenanceWarning -> {
                if (signal.active) {
                    maintenance += signal.kind
                    if (tripActive) tripMaintenance += signal.kind
                } else {
                    maintenance -= signal.kind
                }
            }
            is TripSignal.EnergyLow -> energyLow = signal.low
            is TripSignal.Brake -> brakePressed = signal.pressed
            is TripSignal.Lights -> {
                signal.headlights?.let { headlights = it }
                signal.fog?.let { fogLights = it }
                if (tripActive) tripContext += contextNow()
            }
            is TripSignal.Wiper -> {
                wiperOn = signal.on
                if (tripActive) tripContext += contextNow()
            }
            is TripSignal.Location -> onLocation(LatLng(signal.lat, signal.lng), now, out)
            is TripSignal.Destination -> {
                // 내비는 같은 목적지를 계속 다시 알려 준다. 목적지가 바뀔 때만 '도착'을 지운다.
                val name = signal.name?.takeIf { it.isNotBlank() }
                if (name != destination) {
                    destination = name
                    if (name != null) naviArrived = false
                }
            }
            TripSignal.NaviArrived -> naviArrived = true
            is TripSignal.Odometer -> {
                odometerKm = signal.km
                if (tripActive && tripStartOdometer == null) tripStartOdometer = signal.km
            }
        }

        evaluate(now, out)
        openedDoor?.let { onDoorOpened(it, now, out) }
        return out
    }

    /** 시간이 흘러서 생기는 판단(휴식, 도착, 뒷좌석 미확인)을 처리한다. 1초마다 부른다. */
    @Synchronized
    fun tick(): List<TripEvent> {
        val out = mutableListOf<TripEvent>()
        evaluate(clock(), out)
        return out
    }

    /** 아이 동승을 사람이 직접 정한다. null이면 문·벨트로 추정한다. */
    @Synchronized
    fun setChildOnBoard(value: Boolean?) {
        manualChild = value
        if (value != null) childInRear = value && config.childMode
    }

    @Synchronized
    fun snapshot(): EngineSnapshot {
        val now = clock()
        return EngineSnapshot(
            driving = driving,
            driveBasis = when {
                gearKnown -> DriveBasis.GEAR
                speedKnown -> DriveBasis.SPEED
                uxKnown -> DriveBasis.UX_RESTRICTION
                else -> DriveBasis.NONE
            },
            tripActive = tripActive,
            tripMs = if (tripActive) now - tripStartAt else 0L,
            continuousDrivingMs = continuousMs(now),
            distanceKm = if (tripActive) tripDistanceKm() else null,
            destination = destination,
            naviArrived = naviArrived,
            location = location,
            beltWarnings = activeBeltWarnings(),
            maintenance = maintenance.toSet(),
            energyLow = energyLow,
            childInRear = childInRear,
            rearCheckPending = rearCheckDeadline != null,
            airbagDeployed = airbag == true,
            restCount = tripRestCount,
            hardBrakeCount = tripHardBrakeCount,
            context = contextNow()
        )
    }

    // ------------------------------------------------------------------
    // 주행 판단
    // ------------------------------------------------------------------

    private fun computeDriving(now: Long): Boolean = when {
        gearKnown -> !gearParked
        speedKnown -> speedKmh >= config.movingKmh || (
            driving &&
                doorOpen[DoorPos.DRIVER] != true &&
                lastMovingAt?.let { now - it < config.stoppedHoldMs } == true
            )
        uxKnown -> uxRestricted
        else -> false
    }

    private fun evaluate(now: Long, out: MutableList<TripEvent>) {
        val nowDriving = computeDriving(now)
        if (nowDriving != driving) {
            if (nowDriving) onDriveStart(now, out) else onDriveStop(now, out)
        }
        restRule(now, out)
        beltRule(now, out)
        arrivalRule(now, out)
        rearCheckRule(now, out)
    }

    private fun onDriveStart(now: Long, out: MutableList<TripEvent>) {
        val parkedFor = parkedSince?.let { now - it }
        driving = true
        parkedSince = null
        rearCheckDeadline = null // 다시 출발했으면 뒷좌석 확인 대기는 끝
        out += TripEvent.DrivingChanged(now, true)

        if (parkedFor == null || parkedFor >= config.restResetParkedMs) resetRest()
        segmentStart = now

        if (!tripActive) {
            startTrip(now, out)
        } else if (hasRearSeatData() && manualChild == null && config.childMode) {
            childInRear = anyRearSeated()
        }
        beltDrivingSince = if (activeBeltWarnings().isNotEmpty()) now else null
    }

    private fun onDriveStop(now: Long, out: MutableList<TripEvent>) {
        driving = false
        continuousAccumMs += now - segmentStart
        parkedSince = now
        rearOpenedThisPark = false
        rearCheckDoneThisPark = false
        beltDrivingSince = null
        out += TripEvent.DrivingChanged(now, false)
    }

    private fun resetRest() {
        continuousAccumMs = 0L
        nextRestAtMs = config.restThresholdMs
    }

    private fun continuousMs(now: Long): Long =
        continuousAccumMs + if (driving) now - segmentStart else 0L

    // ------------------------------------------------------------------
    // 출발 · 도착
    // ------------------------------------------------------------------

    private fun startTrip(now: Long, out: MutableList<TripEvent>) {
        tripActive = true
        tripStartAt = now
        tripStartOdometer = odometerKm
        tripPathKm = 0.0
        lastPathPoint = location
        tripRestCount = 0
        tripHardBrakeCount = 0
        tripMaintenance.clear()
        tripMaintenance += maintenance
        tripContext.clear()
        tripContext += contextNow()
        hardBrakeTimes.clear()
        naviArrived = false
        childInRear = decideChildOnBoard(now)

        // 출발 전 체크: 이상이 있을 때만 안내음 한 번씩.
        val belts = activeBeltWarnings()
        if (belts.isNotEmpty()) out += TripEvent.DriverCue(Cue.BELT, now)
        if (maintenance.isNotEmpty()) out += TripEvent.DriverCue(Cue.MAINTENANCE, now)
        if (energyLow) out += TripEvent.DriverCue(Cue.ENERGY, now)

        val issues = buildList {
            belts.forEach { add("${it.label} 벨트 미착용") }
            maintenance.forEach { add("${it.label} 경고") }
            if (energyLow) add("연료·배터리 부족")
        }
        val detail = buildList {
            add(destination?.let { "목적지: $it" } ?: "목적지 미설정")
            add(if (issues.isEmpty()) "출발 전 점검: 이상 없음" else "출발 전 점검: ${issues.joinToString(", ")}")
            if (childInRear) add("뒷좌석 동승 있음")
        }.joinToString(" · ")
        notice(out, NoticeKind.DEPARTED, now, "출발했어요", detail)

        lastLocationSentAt = null
        location?.let { sendLocation(it, now, out) }
    }

    private fun destinationPending() = destination != null && !naviArrived

    private fun arrivalRule(now: Long, out: MutableList<TripEvent>) {
        if (!tripActive || driving) return
        val since = parkedSince ?: return
        val limit = when {
            naviArrived -> config.naviArrivalParkedMs
            destinationPending() -> config.longStopArrivalMs
            else -> config.arrivalParkedMs
        }
        if (now - since >= limit) endTrip(now, out)
    }

    private fun endTrip(now: Long, out: MutableList<TripEvent>) {
        val arrivedAt = parkedSince ?: now
        val km = tripDistanceKm()
        tripActive = false

        val detail = buildList {
            add("운행 ${formatDuration(arrivedAt - tripStartAt)}")
            km?.let { add("${formatKm(it)} km") }
            add("휴식 안내 ${tripRestCount}회")
            if (config.hardBrakeEnabled) add("급제동 ${tripHardBrakeCount}회")
        }.joinToString(" · ")
        val title = destination?.let { "$it 도착했어요" } ?: "도착했어요"
        notice(out, NoticeKind.ARRIVED, now, title, detail, tripContext.toList())

        if (tripMaintenance.isNotEmpty()) {
            notice(
                out, NoticeKind.MAINTENANCE_NEEDED, now, "정비가 필요해요",
                "운행 중 ${tripMaintenance.joinToString(", ") { it.label }} 경고가 있었어요."
            )
        }
        destination = null
        naviArrived = false
    }

    private fun tripDistanceKm(): Double? {
        val start = tripStartOdometer
        val end = odometerKm
        if (start != null && end != null && end >= start) return end - start
        return if (tripPathKm > 0.0) tripPathKm else null
    }

    // ------------------------------------------------------------------
    // 문 · 뒷좌석
    // ------------------------------------------------------------------

    private fun onDoorOpened(pos: DoorPos, now: Long, out: MutableList<TripEvent>) {
        if (pos.isRear) {
            rearOpenedAt = now
            if (!driving) {
                rearOpenedThisPark = true
                rearCheckDeadline = null
                // 운행이 끝난 뒤 뒷문을 열었으면 아이를 내린 것으로 본다.
                if (!tripActive && manualChild == null) childInRear = false
            }
            return
        }
        if (pos != DoorPos.DRIVER || driving) return

        // 운전석 문이 열렸다 = 운전자가 내린다.
        if (tripActive && !destinationPending()) endTrip(now, out)
        maybeAskRearCheck(now, out)
    }

    private fun maybeAskRearCheck(now: Long, out: MutableList<TripEvent>) {
        if (!childInRear || rearCheckDoneThisPark || rearOpenedThisPark) return
        rearCheckDoneThisPark = true
        rearCheckDeadline = now + config.rearCheckGraceMs
        out += TripEvent.DriverCue(Cue.REAR_SEAT_CHECK, now)
    }

    private fun rearCheckRule(now: Long, out: MutableList<TripEvent>) {
        val deadline = rearCheckDeadline ?: return
        if (driving || now < deadline) return
        rearCheckDeadline = null
        out += TripEvent.DriverCue(Cue.REAR_SEAT_CHECK, now)
        notice(
            out, NoticeKind.REAR_SEAT_UNCHECKED, now, "뒷좌석 확인이 안 됐어요",
            "차를 세운 뒤 ${formatDuration(config.rearCheckGraceMs)} 동안 뒷문이 열리지 않았어요. " +
                "뒷좌석에 아이가 남아 있지 않은지 확인해 주세요."
        )
    }

    private fun decideChildOnBoard(now: Long): Boolean {
        if (!config.childMode) return false
        manualChild?.let { return it }
        if (hasRearSeatData()) return anyRearSeated()
        val opened = rearOpenedAt ?: return false
        return now - opened <= config.rearEvidenceWindowMs
    }

    /** 뒷좌석 착석 센서나 벨트 값이 있나. 있으면 문 열림 추정 대신 이 값을 믿는다. */
    private fun hasRearSeatData() = occupied.keys.any { it.isRear } || buckled.keys.any { it.isRear }

    /** 뒷좌석에 누가 있나: 착석 센서가 있으면 그것, 벨트를 맨 사람도 있는 것으로 본다. */
    private fun anyRearSeated() =
        occupied.any { (seat, on) -> seat.isRear && on } || buckled.any { (seat, on) -> seat.isRear && on }

    // ------------------------------------------------------------------
    // 휴식 · 벨트 · 급제동 · 긴급
    // ------------------------------------------------------------------

    private fun restRule(now: Long, out: MutableList<TripEvent>) {
        if (!driving || !tripActive) return
        val continuous = continuousMs(now)
        if (continuous < nextRestAtMs) return
        while (nextRestAtMs <= continuous) nextRestAtMs += config.restRepeatMs
        tripRestCount++
        out += TripEvent.DriverCue(Cue.REST, now)
        notice(
            out, NoticeKind.REST_SUGGESTED, now, "휴식 안내",
            "${formatDuration(continuous)} 동안 쉬지 않고 운전했어요. 차에서 쉬어 가자고 안내했어요."
        )
    }

    /**
     * 지금 벨트를 안 맨 좌석.
     * - 차가 벨트 경고 값을 주면 그것을 그대로 쓴다.
     * - 안 주면 벨트 체결 값으로 대신한다: 운전석은 늘, 다른 좌석은 착석 센서가 '앉음'일 때만
     *   (빈 좌석의 '안 맴'은 경고가 아니므로).
     */
    private fun activeBeltWarnings(): Set<SeatPos> {
        if (beltWarningSource) return beltWarnings.toSet()
        return buckled.filter { (seat, on) -> !on && (seat == SeatPos.DRIVER || occupied[seat] == true) }
            .keys.sortedBy { it.ordinal }.toCollection(linkedSetOf())
    }

    private fun updateBeltTimer(now: Long) {
        if (activeBeltWarnings().isEmpty()) beltDrivingSince = null
        else if (driving && beltDrivingSince == null) beltDrivingSince = now
    }

    private fun beltRule(now: Long, out: MutableList<TripEvent>) {
        val since = beltDrivingSince ?: return
        val belts = activeBeltWarnings()
        if (!driving || belts.isEmpty() || now - since < config.beltGraceMs) return
        beltDrivingSince = now
        notice(
            out, NoticeKind.BELT_UNBUCKLED_DRIVING, now, "안전벨트 미착용",
            "${belts.joinToString(", ") { it.label }} 벨트를 안 맨 채 달리고 있어요."
        )
    }

    private fun onSpeed(kmh: Float, now: Long, out: MutableList<TripEvent>) {
        val previous = speedKmh
        val previousAt = lastSpeedAt
        speedKnown = true
        speedKmh = kmh
        lastSpeedAt = now
        if (kmh >= config.movingKmh) lastMovingAt = now

        if (!config.hardBrakeEnabled || previousAt == null) return
        val dtSec = (now - previousAt) / 1000f
        if (dtSec <= 0f || dtSec > 3f) return
        val decel = (previous - kmh) / dtSec
        val recent = lastHardBrakeAt?.let { now - it < 3_000 } == true
        if (decel >= config.hardBrakeKmhPerSec && brakePressed != false && !recent) onHardBrake(now, out)
    }

    private fun onHardBrake(now: Long, out: MutableList<TripEvent>) {
        lastHardBrakeAt = now
        if (tripActive) tripHardBrakeCount++
        hardBrakeTimes.addLast(now)
        while (hardBrakeTimes.isNotEmpty() && now - hardBrakeTimes.first() > config.hardBrakeWindowMs) {
            hardBrakeTimes.removeFirst()
        }
        if (hardBrakeTimes.size < config.hardBrakeCount) return
        val count = hardBrakeTimes.size
        hardBrakeTimes.clear()
        val sent = notice(
            out, NoticeKind.HARD_BRAKE, now, "급제동이 잦아요",
            "${formatDuration(config.hardBrakeWindowMs)} 동안 급제동 ${count}회. 차에서 천천히 가자고 안내했어요."
        )
        if (sent) out += TripEvent.DriverCue(Cue.SLOW_DOWN, now)
    }

    private fun emergency(now: Long, out: MutableList<TripEvent>) {
        val where = location?.let { "마지막 위치 ${formatLatLng(it)}" } ?: "위치 정보 없음"
        notice(
            out, NoticeKind.EMERGENCY_AIRBAG, now, "에어백이 터졌어요",
            "사고가 난 것 같아요. $where. 바로 연락해 보세요."
        )
    }

    // ------------------------------------------------------------------
    // 위치
    // ------------------------------------------------------------------

    private fun onLocation(point: LatLng, now: Long, out: MutableList<TripEvent>) {
        if (tripActive && driving) {
            lastPathPoint?.let { previous ->
                val d = haversineKm(previous, point)
                if (d < MAX_LOCATION_JUMP_KM) tripPathKm += d
            }
            lastPathPoint = point
        }
        location = point
        if (!tripActive) return
        val last = lastLocationSentAt
        if (last == null || now - last >= config.locationIntervalMs) sendLocation(point, now, out)
    }

    private fun sendLocation(point: LatLng, now: Long, out: MutableList<TripEvent>) {
        lastLocationSentAt = now
        out += TripEvent.LocationUpdate(now, point, destination)
    }

    // ------------------------------------------------------------------
    // 알림 공통
    // ------------------------------------------------------------------

    private fun contextNow(): List<String> = buildList {
        if (headlights) add("전조등 켬")
        if (fogLights) add("안개등 켬")
        if (wiperOn) add("와이퍼 작동")
    }

    private fun notice(
        out: MutableList<TripEvent>,
        kind: NoticeKind,
        now: Long,
        title: String,
        detail: String,
        context: List<String> = contextNow()
    ): Boolean {
        if (kind in COOLDOWN_KINDS) {
            val last = lastNoticeAt[kind]
            if (last != null && now - last < config.cooldownMs) return false
        }
        lastNoticeAt[kind] = now
        out += TripEvent.Notice(kind, now, title, detail, context, location)
        return true
    }

    companion object {
        /** 같은 종류가 짧은 간격으로 반복되면 가족에게 다시 보내지 않는 알림. */
        private val COOLDOWN_KINDS = setOf(
            NoticeKind.BELT_UNBUCKLED_DRIVING,
            NoticeKind.HARD_BRAKE,
            NoticeKind.MAINTENANCE_NEEDED
        )

        /** 위치가 한 번에 이만큼 넘게 튀면 GPS 오류로 보고 거리에 넣지 않는다. */
        private const val MAX_LOCATION_JUMP_KM = 2.0

        fun haversineKm(a: LatLng, b: LatLng): Double {
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
    }
}
