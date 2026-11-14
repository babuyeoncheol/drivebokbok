package kr.ansimgwiga.car.core

/*
 * 판단 로직이 쓰는 입력·출력 모델.
 * 이 패키지(core)는 안드로이드 의존성이 없어서 일반 JVM 단위 테스트로 검증할 수 있다.
 */

enum class DoorPos(val label: String) {
    DRIVER("운전석 문"), PASSENGER("조수석 문"), REAR_LEFT("뒷좌석 왼쪽 문"), REAR_RIGHT("뒷좌석 오른쪽 문");
    val isRear: Boolean get() = this == REAR_LEFT || this == REAR_RIGHT
}

enum class SeatPos(val label: String) {
    DRIVER("운전석"), PASSENGER("조수석"), REAR_LEFT("뒷좌석 왼쪽"), REAR_CENTER("뒷좌석 가운데"), REAR_RIGHT("뒷좌석 오른쪽");
    val isRear: Boolean get() = this == REAR_LEFT || this == REAR_CENTER || this == REAR_RIGHT
}

enum class Maintenance(val label: String) {
    ENGINE_OIL("엔진오일"),
    BRAKE_FLUID("브레이크액"),
    TRANSMISSION_OIL("변속기오일"),
    WASHER_FLUID("워셔액"),
    POWER_DOWN("파워다운"),
    TIRE_PRESSURE("타이어 공기압")
}

data class LatLng(val lat: Double, val lng: Double)

/** 차량·내비·표준 API·시뮬레이터에서 들어오는 신호. 출처와 상관없이 같은 모양으로 엔진에 들어간다. */
sealed interface TripSignal {
    /** 기어가 P인지. 표준 API(GEAR_SELECTION)나 시뮬레이터가 보낸다. */
    data class Gear(val parked: Boolean) : TripSignal
    data class Speed(val kmh: Float) : TripSignal
    /** CarUxRestrictions의 '주행 중 제한' 여부. 기어·속도가 없을 때 주행 판단에 쓴다. */
    data class UxRestricted(val restricted: Boolean) : TripSignal
    data class Door(val pos: DoorPos, val open: Boolean) : TripSignal
    /** 안전벨트 미착용 경고. */
    data class BeltWarning(val seat: SeatPos, val active: Boolean) : TripSignal
    /** 좌석별 벨트 체결(있으면 뒷좌석 탑승 근거로 쓴다). 벨트 경고 값이 없는 차에서는 벨트 경고 대신 쓴다. */
    data class SeatBuckled(val seat: SeatPos, val buckled: Boolean) : TripSignal
    /** 좌석에 사람이 앉아 있는지(착석 센서). 뒷좌석 동승 판단의 가장 확실한 근거. */
    data class SeatOccupied(val seat: SeatPos, val occupied: Boolean) : TripSignal
    data class Airbag(val deployed: Boolean) : TripSignal
    data class MaintenanceWarning(val kind: Maintenance, val active: Boolean) : TripSignal
    data class EnergyLow(val low: Boolean) : TripSignal
    data class Brake(val pressed: Boolean) : TripSignal
    data class Lights(val headlights: Boolean? = null, val fog: Boolean? = null) : TripSignal
    data class Wiper(val on: Boolean) : TripSignal
    data class Location(val lat: Double, val lng: Double) : TripSignal
    data class Destination(val name: String?) : TripSignal
    data object NaviArrived : TripSignal
    /** 누적 주행 거리(km). 출발·도착 값의 차이로 운행 거리를 계산한다. */
    data class Odometer(val km: Double) : TripSignal
}

enum class Level { INFO, CAUTION, EMERGENCY }

enum class NoticeKind(val level: Level) {
    DEPARTED(Level.INFO),
    REST_SUGGESTED(Level.INFO),
    MAINTENANCE_NEEDED(Level.INFO),
    ARRIVED(Level.INFO),
    BELT_UNBUCKLED_DRIVING(Level.CAUTION),
    HARD_BRAKE(Level.CAUTION),
    REAR_SEAT_UNCHECKED(Level.CAUTION),
    EMERGENCY_AIRBAG(Level.EMERGENCY),
    /** 가족이 보낸 응원 메시지를 차에서 들려줬음(가족 앱에 '들었어요' 표시). */
    CHEER_PLAYED(Level.INFO)
}

/** 운전자에게 들려줄 안내. 녹음 파일 이름(res/raw)과 TTS용 문장을 함께 가진다. */
enum class Cue(val clipName: String, val text: String) {
    BELT("cue_belt", "안전벨트를 매 주세요."),
    MAINTENANCE("cue_maintenance", "차량 점검 경고가 있어요. 운행 후 확인해 주세요."),
    ENERGY("cue_energy", "연료나 배터리가 부족해요."),
    REST("cue_rest", "오래 운전하셨어요. 잠시 쉬어 가세요."),
    REAR_SEAT_CHECK("cue_rear_seat", "뒷좌석을 확인해 주세요."),
    SLOW_DOWN("cue_slow_down", "급제동이 잦아요. 천천히 가세요."),
    CHEER_SAFE("cue_cheer_safe", "가족 메시지예요. 조심히 와요."),
    CHEER_SLOW("cue_cheer_slow", "가족 메시지예요. 천천히 와도 괜찮아요."),
    CHEER_CALL("cue_cheer_call", "가족 메시지예요. 도착하면 연락 줘요."),
    CHEER_LOVE("cue_cheer_love", "가족 메시지예요. 사랑해요, 기다리고 있어요.")
}

/**
 * 가족 앱에서 고르는 응원 메시지. 차에서는 글자를 읽어 주는 대신 녹음해 둔 안내음을 튼다
 * (Workspace에 음성 합성 SDK가 없어서). 서버(cheers.preset)와 가족 앱이 같은 이름을 쓴다.
 */
enum class CheerPreset(val label: String, val cue: Cue) {
    SAFE("조심히 와요", Cue.CHEER_SAFE),
    SLOW("천천히 와도 괜찮아요", Cue.CHEER_SLOW),
    CALL("도착하면 연락 줘요", Cue.CHEER_CALL),
    LOVE("사랑해요, 기다리고 있어요", Cue.CHEER_LOVE);

    companion object {
        fun of(name: String?): CheerPreset? = entries.firstOrNull { it.name == name }
    }
}

sealed interface TripEvent {
    val atMs: Long

    /** 가족에게 보내는 알림. */
    data class Notice(
        val kind: NoticeKind,
        override val atMs: Long,
        val title: String,
        val detail: String,
        val context: List<String> = emptyList(),
        val location: LatLng? = null
    ) : TripEvent

    /** 운전자에게 들려주는 안내음. */
    data class DriverCue(val cue: Cue, override val atMs: Long) : TripEvent

    /** 가족 앱 지도에 쓰는 위치 갱신(알림 아님). */
    data class LocationUpdate(
        override val atMs: Long,
        val location: LatLng,
        val destination: String?
    ) : TripEvent

    data class DrivingChanged(override val atMs: Long, val driving: Boolean) : TripEvent
}

data class EngineConfig(
    /** 이 시간 넘게 쉬지 않고 달리면 휴식 안내. */
    val restThresholdMs: Long = 120 * MIN,
    /** 첫 휴식 안내 뒤 다시 안내할 간격. */
    val restRepeatMs: Long = 30 * MIN,
    /** 이 시간 넘게 주차하면 연속 운전 시간을 0으로. */
    val restResetParkedMs: Long = 10 * MIN,
    /** 벨트 경고가 켜진 채 이 시간 넘게 달리면 가족에게 '주의'. */
    val beltGraceMs: Long = 30 * SEC,
    /** 뒷좌석 확인 안내 뒤 이 시간 안에 뒷문이 안 열리면 가족에게 알림. */
    val rearCheckGraceMs: Long = 3 * MIN,
    /** 아이 동승 모드. 끄면 뒷좌석 확인을 하지 않는다. */
    val childMode: Boolean = true,
    /** 뒷좌석 벨트 정보가 없을 때: 출발 전 이 시간 안에 뒷문이 열렸으면 뒷좌석에 사람이 탔다고 본다. */
    val rearEvidenceWindowMs: Long = 15 * MIN,
    /** 같은 종류 알림 사이 최소 간격(긴급은 예외). */
    val cooldownMs: Long = 5 * MIN,
    /** 가족 앱 위치 갱신 간격. */
    val locationIntervalMs: Long = 30 * SEC,
    /** 주차 상태로 이 시간이 지나면 문이 안 열려도 도착으로 본다. */
    val arrivalParkedMs: Long = 5 * MIN,
    /** 목적지를 향해 가는 중(내비 도착 전)에 세운 차는 '잠시 정차'로 보고, 이 시간이 지나야 도착으로 본다. */
    val longStopArrivalMs: Long = 30 * MIN,
    /** 내비 도착 이벤트 뒤 주차가 이만큼 이어지면 도착으로 본다. */
    val naviArrivalParkedMs: Long = 60 * SEC,
    /** 속도로 주행을 판단할 때 기준. */
    val movingKmh: Float = 5f,
    val stoppedHoldMs: Long = 30 * SEC,
    /** 2차 기능: 급제동 케어. */
    val hardBrakeEnabled: Boolean = false,
    val hardBrakeKmhPerSec: Float = 10f,
    val hardBrakeCount: Int = 3,
    val hardBrakeWindowMs: Long = 10 * MIN
) {
    companion object {
        const val SEC = 1_000L
        const val MIN = 60_000L

        /** 시연용: 시간을 짧게 줄인 설정. */
        fun demo() = EngineConfig(
            restThresholdMs = 2 * MIN,
            restRepeatMs = 1 * MIN,
            rearCheckGraceMs = 20 * SEC,
            arrivalParkedMs = 1 * MIN,
            longStopArrivalMs = 3 * MIN,
            cooldownMs = 30 * SEC,
            locationIntervalMs = 5 * SEC,
            hardBrakeEnabled = true
        )
    }
}

/** 주행 여부를 어떤 신호로 판단했는지(진단 화면에 보여 준다). */
enum class DriveBasis(val label: String) { GEAR("기어"), SPEED("속도"), UX_RESTRICTION("주행 제한 상태"), NONE("신호 없음") }

data class EngineSnapshot(
    val driving: Boolean,
    val driveBasis: DriveBasis,
    val tripActive: Boolean,
    val tripMs: Long,
    val continuousDrivingMs: Long,
    val distanceKm: Double?,
    val destination: String?,
    val naviArrived: Boolean,
    val location: LatLng?,
    val beltWarnings: Set<SeatPos>,
    val maintenance: Set<Maintenance>,
    val energyLow: Boolean,
    val childInRear: Boolean,
    val rearCheckPending: Boolean,
    val airbagDeployed: Boolean,
    val restCount: Int,
    val hardBrakeCount: Int,
    val context: List<String>
)
