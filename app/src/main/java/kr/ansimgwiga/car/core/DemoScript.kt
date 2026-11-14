package kr.ansimgwiga.car.core

/**
 * 시연용 대본. 실제 시간으로 약 1분 30초 동안 '아이를 태우고 귀가 → 휴식 안내 → 급제동 → 비 → 도착 → 뒷좌석 미확인'을 재생한다.
 * 긴 대기(2시간 운전, 3분 기다림)는 [DemoStep.warpMs]로 시계를 앞으로 돌려서 건너뛴다.
 * 어떤 설정(기본·시연)으로 돌려도 같은 알림이 나오도록 시간을 넉넉하게 잡았다.
 */
data class DemoStep(
    val atSec: Int,
    val narration: String?,
    val signals: List<TripSignal> = emptyList(),
    val warpMs: Long = 0L
)

object DemoScript {

    private val START = LatLng(37.4380, 127.1050)
    private val HOME = LatLng(37.4020, 127.1090)
    const val DESTINATION = "집"
    const val START_ODOMETER_KM = 12_000.0

    fun steps(): List<DemoStep> {
        val s = mutableListOf<DemoStep>()

        s += DemoStep(
            0, "차에 탈 준비. 기어 P, 운전석 벨트 경고, 워셔액 부족 경고, 밤이라 전조등 켬",
            listOf(
                TripSignal.Gear(parked = true),
                TripSignal.Speed(0f),
                TripSignal.Airbag(deployed = false),
                TripSignal.Brake(pressed = false),
                TripSignal.Lights(headlights = true, fog = false),
                TripSignal.Wiper(on = false),
                TripSignal.EnergyLow(low = false),
                TripSignal.Odometer(START_ODOMETER_KM),
                TripSignal.Location(START.lat, START.lng),
                TripSignal.Destination(DESTINATION),
                TripSignal.BeltWarning(SeatPos.DRIVER, active = true),
                TripSignal.MaintenanceWarning(Maintenance.WASHER_FLUID, active = true)
            ) + DoorPos.entries.map { TripSignal.Door(it, open = false) }
        )
        s += DemoStep(3, "아이를 뒷좌석에 태워요", listOf(TripSignal.Door(DoorPos.REAR_LEFT, open = true)))
        s += DemoStep(5, null, listOf(TripSignal.Door(DoorPos.REAR_LEFT, open = false)))
        s += DemoStep(7, "운전자가 타요", listOf(TripSignal.Door(DoorPos.DRIVER, open = true)))
        s += DemoStep(8, null, listOf(TripSignal.Door(DoorPos.DRIVER, open = false)))
        s += DemoStep(
            10, "기어 D로 출발. 벨트·점검 안내음, 가족에게 출발 알림",
            listOf(TripSignal.Gear(parked = false), TripSignal.Speed(12f))
        )
        s += DemoStep(13, "벨트를 맸어요", listOf(TripSignal.BeltWarning(SeatPos.DRIVER, active = false)))

        // 13~29초: 집 쪽으로 이동(2초마다 위치, 속도).
        val drivePoints = 8
        for (i in 1..drivePoints) {
            val p = lerp(START, HOME, i / (drivePoints + 4.0))
            s += DemoStep(
                13 + i * 2, if (i == 1) "주행 중. 가족 폰에 위치가 갱신돼요" else null,
                listOf(TripSignal.Speed(40f + i * 2), TripSignal.Location(p.lat, p.lng))
            )
        }

        s += DemoStep(
            31, "시간을 2시간 5분 앞으로. 휴식 안내음, 가족에게 휴식 안내",
            listOf(TripSignal.Odometer(START_ODOMETER_KM + 160.0)),
            warpMs = 125 * EngineConfig.MIN
        )

        // 35~44초: 급제동 세 번(2차 기능을 켰을 때만 알림).
        s += DemoStep(35, "급제동이 이어져요", listOf(TripSignal.Speed(60f)))
        // 속도는 1초마다 넣는다(실제 센서처럼). 급제동 = 1초에 25 km/h 감속 + 브레이크 페달.
        for ((k, t) in listOf(36, 39, 42).withIndex()) {
            s += DemoStep(t, null, listOf(TripSignal.Brake(true), TripSignal.Speed(35f)))
            s += DemoStep(t + 1, null, listOf(TripSignal.Brake(false), TripSignal.Speed(50f)))
            s += DemoStep(t + 2, null, listOf(TripSignal.Speed(if (k == 2) 45f else 60f)))
        }

        s += DemoStep(47, "비가 와서 와이퍼를 켜요", listOf(TripSignal.Wiper(on = true)))
        for (i in 1..3) {
            val p = lerp(START, HOME, (drivePoints + i) / (drivePoints + 4.0))
            s += DemoStep(47 + i * 2, null, listOf(TripSignal.Speed(35f), TripSignal.Location(p.lat, p.lng)))
        }

        s += DemoStep(
            55, "집 앞 도착. 내비 도착, 기어 P",
            listOf(
                TripSignal.Location(HOME.lat, HOME.lng),
                TripSignal.NaviArrived,
                TripSignal.Speed(0f),
                TripSignal.Gear(parked = true),
                TripSignal.Odometer(START_ODOMETER_KM + 172.4)
            )
        )
        s += DemoStep(
            58, "운전자만 내려요. 가족에게 도착·정비 알림, 차에서 뒷좌석 확인 안내음",
            listOf(TripSignal.Door(DoorPos.DRIVER, open = true))
        )
        s += DemoStep(60, null, listOf(TripSignal.Door(DoorPos.DRIVER, open = false)))
        s += DemoStep(
            64, "3분이 지나도 뒷문이 안 열려요. 가족에게 뒷좌석 확인 알림",
            warpMs = 3 * EngineConfig.MIN + 10 * EngineConfig.SEC
        )
        s += DemoStep(72, "뒷문을 열어 아이를 내려요. 끝", listOf(TripSignal.Door(DoorPos.REAR_LEFT, open = true)))
        s += DemoStep(74, null, listOf(TripSignal.Door(DoorPos.REAR_LEFT, open = false)))
        return s.sortedBy { it.atSec }
    }

    val lengthSec: Int get() = steps().maxOf { it.atSec }

    /** 출발지(0.0)에서 집(1.0)까지 길 위의 한 점. 시뮬레이터 '위치 이동' 버튼이 쓴다. */
    fun pointAt(t: Double): LatLng = lerp(START, HOME, t.coerceIn(0.0, 1.0))

    private fun lerp(a: LatLng, b: LatLng, t: Double) =
        LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
}
