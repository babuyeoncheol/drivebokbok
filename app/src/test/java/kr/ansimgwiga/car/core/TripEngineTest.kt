package kr.ansimgwiga.car.core

import kr.ansimgwiga.car.core.EngineConfig.Companion.MIN
import kr.ansimgwiga.car.core.EngineConfig.Companion.SEC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {

    /** 테스트용 시계와 엔진. 모든 이벤트를 [events]에 모은다. */
    private class Rig(config: EngineConfig = EngineConfig()) {
        var now = 1_000_000L
        val engine = TripEngine(config) { now }
        val events = mutableListOf<TripEvent>()

        fun send(vararg signals: TripSignal) = signals.forEach { events += engine.onSignal(it) }

        /** 1초 간격으로 tick을 부르며 시간을 보낸다. */
        fun advance(ms: Long, step: Long = SEC) {
            var left = ms
            while (left > 0) {
                val d = minOf(step, left)
                now += d
                left -= d
                events += engine.tick()
            }
        }

        fun notices(kind: NoticeKind) = events.filterIsInstance<TripEvent.Notice>().filter { it.kind == kind }
        fun cues(cue: Cue) = events.filterIsInstance<TripEvent.DriverCue>().filter { it.cue == cue }
        fun clear() = events.clear()

        fun parkedBaseline() = send(
            TripSignal.Gear(parked = true),
            TripSignal.Airbag(deployed = false),
            *DoorPos.entries.map { TripSignal.Door(it, false) }.toTypedArray()
        )

        fun depart() = send(TripSignal.Gear(parked = false))
        fun park() = send(TripSignal.Gear(parked = true))
        fun openClose(pos: DoorPos) = send(TripSignal.Door(pos, true), TripSignal.Door(pos, false))
    }

    // ---------------- 출발 전 체크 ----------------

    @Test
    fun departure_reportsChecksAndPlaysCues() {
        val r = Rig()
        r.parkedBaseline()
        r.send(
            TripSignal.BeltWarning(SeatPos.DRIVER, true),
            TripSignal.MaintenanceWarning(Maintenance.ENGINE_OIL, true),
            TripSignal.EnergyLow(true),
            TripSignal.Destination("집")
        )
        r.depart()

        assertEquals(1, r.cues(Cue.BELT).size)
        assertEquals(1, r.cues(Cue.MAINTENANCE).size)
        assertEquals(1, r.cues(Cue.ENERGY).size)
        val departed = r.notices(NoticeKind.DEPARTED).single()
        assertTrue(departed.detail, departed.detail.contains("목적지: 집"))
        assertTrue(departed.detail, departed.detail.contains("운전석 벨트 미착용"))
        assertTrue(departed.detail, departed.detail.contains("엔진오일 경고"))
        assertTrue(departed.detail, departed.detail.contains("연료·배터리 부족"))
    }

    // ---------------- 벨트 경고 값이 없는 차(Pleos 에뮬레이터) ----------------

    @Test
    fun belt_fallsBackToBuckledState_whenCarHasNoBeltWarning() {
        val r = Rig()
        r.parkedBaseline()
        // 벨트 경고 값은 안 오고 체결 값만 온다. 빈 조수석의 '안 맴'은 경고가 아니다.
        r.send(TripSignal.SeatBuckled(SeatPos.DRIVER, false), TripSignal.SeatBuckled(SeatPos.PASSENGER, false))
        r.depart()
        assertEquals(1, r.cues(Cue.BELT).size)
        val departed = r.notices(NoticeKind.DEPARTED).single()
        assertTrue(departed.detail, departed.detail.contains("운전석 벨트 미착용"))
        assertFalse(departed.detail, departed.detail.contains("조수석"))
        assertEquals(setOf(SeatPos.DRIVER), r.engine.snapshot().beltWarnings)

        // 매면 경고가 풀리고, 계속 안 매고 달리면 가족 알림
        r.send(TripSignal.SeatBuckled(SeatPos.DRIVER, true))
        assertTrue(r.engine.snapshot().beltWarnings.isEmpty())
        r.advance(10 * MIN, step = MIN)
        assertTrue(r.notices(NoticeKind.BELT_UNBUCKLED_DRIVING).isEmpty())
    }

    @Test
    fun belt_fallback_includesOccupiedPassenger_andNotifiesWhileDriving() {
        val r = Rig()
        r.parkedBaseline()
        r.send(
            TripSignal.SeatBuckled(SeatPos.DRIVER, true),
            TripSignal.SeatOccupied(SeatPos.PASSENGER, true),
            TripSignal.SeatBuckled(SeatPos.PASSENGER, false)
        )
        r.depart()
        assertEquals(setOf(SeatPos.PASSENGER), r.engine.snapshot().beltWarnings)
        r.advance(10 * MIN, step = MIN)
        val n = r.notices(NoticeKind.BELT_UNBUCKLED_DRIVING)
        assertTrue(n.isNotEmpty())
        assertTrue(n.first().detail, n.first().detail.contains("조수석"))
    }

    @Test
    fun belt_warningSignal_winsOverBuckledFallback() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.SeatBuckled(SeatPos.DRIVER, false), TripSignal.BeltWarning(SeatPos.DRIVER, false))
        r.depart()
        assertTrue("차가 경고 값을 주면 그 값을 믿는다", r.cues(Cue.BELT).isEmpty())
    }

    // ---------------- 착석 센서로 뒷좌석 동승 ----------------

    @Test
    fun rearCheck_usesSeatOccupancy_withoutDoorOrBelt() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.SeatOccupied(SeatPos.REAR_RIGHT, true), TripSignal.SeatBuckled(SeatPos.REAR_RIGHT, false))
        r.depart()
        assertTrue(r.engine.snapshot().childInRear)
        r.advance(15 * MIN, step = MIN)
        r.park()
        r.openClose(DoorPos.DRIVER)
        assertEquals(1, r.cues(Cue.REAR_SEAT_CHECK).size)
    }

    @Test
    fun rearCheck_emptyRearSeats_byOccupancy_noCue_evenIfRearDoorOpened() {
        val r = Rig()
        r.parkedBaseline()
        r.openClose(DoorPos.REAR_LEFT) // 짐만 싣고
        r.send(*SeatPos.entries.filter { it.isRear }.map { TripSignal.SeatOccupied(it, false) }.toTypedArray())
        r.depart()
        assertFalse("착석 센서가 비었다고 하면 문 열림 추정보다 센서를 믿는다", r.engine.snapshot().childInRear)
        r.advance(15 * MIN, step = MIN)
        r.park()
        r.openClose(DoorPos.DRIVER)
        assertTrue(r.cues(Cue.REAR_SEAT_CHECK).isEmpty())
    }

    @Test
    fun departure_withoutIssues_saysAllClear_andNoCues() {
        val r = Rig()
        r.parkedBaseline()
        r.depart()
        val departed = r.notices(NoticeKind.DEPARTED).single()
        assertTrue(departed.detail.contains("이상 없음"))
        assertTrue(departed.detail.contains("목적지 미설정"))
        assertTrue(r.events.none { it is TripEvent.DriverCue })
    }

    @Test
    fun departure_sendsLocationRightAway() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.Location(37.4, 127.1))
        assertTrue("운행 전엔 위치를 보내지 않는다", r.events.none { it is TripEvent.LocationUpdate })
        r.depart()
        assertEquals(1, r.events.filterIsInstance<TripEvent.LocationUpdate>().size)
    }

    // ---------------- 휴식 안내 ----------------

    @Test
    fun rest_firesAtThreshold_thenRepeats() {
        val r = Rig()
        r.parkedBaseline()
        r.depart()
        r.advance(119 * MIN, step = 30 * SEC)
        assertTrue(r.notices(NoticeKind.REST_SUGGESTED).isEmpty())
        r.advance(1 * MIN)
        assertEquals(1, r.notices(NoticeKind.REST_SUGGESTED).size)
        assertEquals(1, r.cues(Cue.REST).size)
        r.advance(29 * MIN, step = 30 * SEC)
        assertEquals(1, r.notices(NoticeKind.REST_SUGGESTED).size)
        r.advance(1 * MIN)
        assertEquals(2, r.notices(NoticeKind.REST_SUGGESTED).size)
        assertEquals(2, r.engine.snapshot().restCount)
    }

    @Test
    fun rest_resetsAfterLongStop_butNotAfterShortStop() {
        // 목적지를 향해 가는 중이라 정차해도 운행은 이어진다.
        val long = Rig()
        long.parkedBaseline()
        long.send(TripSignal.Destination("부산"))
        long.depart()
        long.advance(100 * MIN, step = MIN)
        long.park()
        long.advance(11 * MIN, step = MIN)
        long.depart()
        long.advance(100 * MIN, step = MIN)
        assertTrue("10분 넘게 쉬면 연속 운전 시간이 0으로", long.notices(NoticeKind.REST_SUGGESTED).isEmpty())
        assertTrue(long.engine.snapshot().tripActive)

        val short = Rig()
        short.parkedBaseline()
        short.send(TripSignal.Destination("부산"))
        short.depart()
        short.advance(100 * MIN, step = MIN)
        short.park()
        short.advance(5 * MIN, step = MIN)
        short.depart()
        short.advance(20 * MIN, step = MIN)
        assertEquals("짧게 서면 이어서 센다", 1, short.notices(NoticeKind.REST_SUGGESTED).size)
    }

    // ---------------- 긴급 알림 ----------------

    @Test
    fun airbag_changeToDeployed_sendsEmergencyWithLocation() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.Location(37.5, 127.0))
        r.depart()
        r.send(TripSignal.Airbag(true))
        val n = r.notices(NoticeKind.EMERGENCY_AIRBAG).single()
        assertEquals(Level.EMERGENCY, n.kind.level)
        assertEquals(LatLng(37.5, 127.0), n.location)
        assertTrue(n.detail.contains("37.50000"))
    }

    @Test
    fun airbag_alreadyDeployedAtStart_isOnlyABaseline() {
        val r = Rig()
        r.send(TripSignal.Airbag(true))
        assertTrue(r.notices(NoticeKind.EMERGENCY_AIRBAG).isEmpty())
        assertTrue(r.engine.snapshot().airbagDeployed)
    }

    // ---------------- 도착 ----------------

    @Test
    fun arrival_byDriverDoor_whenNoDestination() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.Odometer(100.0))
        r.depart()
        r.advance(20 * MIN, step = MIN)
        r.send(TripSignal.Odometer(112.34))
        r.park()
        r.advance(10 * SEC)
        r.openClose(DoorPos.DRIVER)
        val arrived = r.notices(NoticeKind.ARRIVED).single()
        assertEquals("도착했어요", arrived.title)
        assertTrue(arrived.detail, arrived.detail.contains("운행 20분"))
        assertTrue(arrived.detail, arrived.detail.contains("12.3 km"))
        r.openClose(DoorPos.DRIVER)
        r.advance(10 * MIN, step = MIN)
        assertEquals("한 번만", 1, r.notices(NoticeKind.ARRIVED).size)
        assertFalse(r.engine.snapshot().tripActive)
    }

    @Test
    fun arrival_waitsForNavi_whenDestinationIsPending() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.Destination("할머니 댁"))
        r.depart()
        r.advance(30 * MIN, step = MIN)
        r.park()
        r.openClose(DoorPos.DRIVER) // 휴게소
        r.advance(29 * MIN, step = MIN)
        assertTrue(r.notices(NoticeKind.ARRIVED).isEmpty())
        r.advance(1 * MIN)
        val arrived = r.notices(NoticeKind.ARRIVED).single()
        assertEquals("할머니 댁 도착했어요", arrived.title)
    }

    @Test
    fun arrival_byNaviArrived_afterParkedAMinute() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.Destination("집"))
        r.depart()
        r.advance(10 * MIN, step = MIN)
        r.send(TripSignal.NaviArrived)
        r.park()
        r.advance(59 * SEC)
        assertTrue(r.notices(NoticeKind.ARRIVED).isEmpty())
        r.advance(1 * SEC)
        assertEquals(1, r.notices(NoticeKind.ARRIVED).size)
    }

    @Test
    fun arrival_notUndone_whenNaviRepeatsSameDestination() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.Destination("집"))
        r.depart()
        r.advance(10 * MIN, step = MIN)
        r.send(TripSignal.NaviArrived)
        r.park()
        r.advance(30 * SEC)
        r.send(TripSignal.Destination("집")) // 내비가 30초마다 다시 알려 줌
        r.advance(30 * SEC)
        assertEquals(1, r.notices(NoticeKind.ARRIVED).size)
    }

    @Test
    fun arrival_withoutDoorData_afterParkedFiveMinutes() {
        val r = Rig()
        r.send(TripSignal.Gear(parked = true))
        r.depart()
        r.advance(10 * MIN, step = MIN)
        r.park()
        r.advance(5 * MIN)
        assertEquals(1, r.notices(NoticeKind.ARRIVED).size)
    }

    @Test
    fun arrival_followedByMaintenanceNotice_whenWarningsHappened() {
        val r = Rig()
        r.parkedBaseline()
        r.depart()
        r.send(TripSignal.MaintenanceWarning(Maintenance.BRAKE_FLUID, true))
        r.send(TripSignal.MaintenanceWarning(Maintenance.BRAKE_FLUID, false))
        r.park()
        r.openClose(DoorPos.DRIVER)
        val notices = r.events.filterIsInstance<TripEvent.Notice>().map { it.kind }
        assertEquals(listOf(NoticeKind.DEPARTED, NoticeKind.ARRIVED, NoticeKind.MAINTENANCE_NEEDED), notices)
        assertTrue(r.notices(NoticeKind.MAINTENANCE_NEEDED).single().detail.contains("브레이크액"))
    }

    // ---------------- 뒷좌석 확인 ----------------

    private fun Rig.departWithChildByDoor() {
        parkedBaseline()
        openClose(DoorPos.REAR_LEFT)
        advance(30 * SEC)
        openClose(DoorPos.DRIVER)
        depart()
    }

    @Test
    fun rearCheck_unresolved_notifiesFamilyAfterGrace() {
        val r = Rig()
        r.departWithChildByDoor()
        assertTrue(r.engine.snapshot().childInRear)
        assertTrue(r.notices(NoticeKind.DEPARTED).single().detail.contains("뒷좌석 동승 있음"))
        r.advance(15 * MIN, step = MIN)
        r.park()
        r.openClose(DoorPos.DRIVER)
        assertEquals(1, r.cues(Cue.REAR_SEAT_CHECK).size)
        assertTrue(r.engine.snapshot().rearCheckPending)
        r.advance(3 * MIN - SEC)
        assertTrue(r.notices(NoticeKind.REAR_SEAT_UNCHECKED).isEmpty())
        r.advance(1 * SEC)
        val n = r.notices(NoticeKind.REAR_SEAT_UNCHECKED).single()
        assertEquals(Level.CAUTION, n.kind.level)
        assertEquals("안내음 한 번 더", 2, r.cues(Cue.REAR_SEAT_CHECK).size)
        r.advance(10 * MIN, step = MIN)
        assertEquals("한 번만", 1, r.notices(NoticeKind.REAR_SEAT_UNCHECKED).size)
    }

    @Test
    fun rearCheck_resolvedWhenRearDoorOpens() {
        val r = Rig()
        r.departWithChildByDoor()
        r.advance(15 * MIN, step = MIN)
        r.park()
        r.openClose(DoorPos.DRIVER)
        r.advance(30 * SEC)
        r.openClose(DoorPos.REAR_LEFT)
        r.advance(10 * MIN, step = MIN)
        assertTrue(r.notices(NoticeKind.REAR_SEAT_UNCHECKED).isEmpty())
        assertFalse(r.engine.snapshot().childInRear)
    }

    @Test
    fun rearCheck_cancelledWhenDrivingAgain() {
        val r = Rig()
        r.departWithChildByDoor()
        r.send(TripSignal.Destination("학원"))
        r.advance(15 * MIN, step = MIN)
        r.park()
        r.openClose(DoorPos.DRIVER) // 편의점에 잠깐
        r.advance(1 * MIN)
        r.depart()
        r.advance(5 * MIN, step = MIN)
        assertTrue(r.notices(NoticeKind.REAR_SEAT_UNCHECKED).isEmpty())
    }

    @Test
    fun rearCheck_notAsked_withoutRearPassenger() {
        val r = Rig()
        r.parkedBaseline()
        r.openClose(DoorPos.DRIVER)
        r.depart()
        r.advance(15 * MIN, step = MIN)
        r.park()
        r.openClose(DoorPos.DRIVER)
        r.advance(5 * MIN, step = MIN)
        assertTrue(r.cues(Cue.REAR_SEAT_CHECK).isEmpty())
        assertTrue(r.notices(NoticeKind.REAR_SEAT_UNCHECKED).isEmpty())
    }

    @Test
    fun rearCheck_ignoresOldRearDoorOpening() {
        val r = Rig()
        r.parkedBaseline()
        r.openClose(DoorPos.REAR_RIGHT)
        r.advance(20 * MIN, step = MIN) // 짐을 넣고 한참 뒤 출발
        r.depart()
        assertFalse(r.engine.snapshot().childInRear)
    }

    @Test
    fun rearCheck_usesRearBeltWhenAvailable() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.SeatBuckled(SeatPos.REAR_RIGHT, false))
        r.depart()
        assertFalse(r.engine.snapshot().childInRear)
        r.send(TripSignal.SeatBuckled(SeatPos.REAR_RIGHT, true))
        assertTrue(r.engine.snapshot().childInRear)
        r.advance(5 * MIN, step = MIN)
        r.park()
        r.openClose(DoorPos.DRIVER)
        assertEquals(1, r.cues(Cue.REAR_SEAT_CHECK).size)
    }

    @Test
    fun rearCheck_manualSwitchWins_andChildModeOffDisablesIt() {
        val manual = Rig()
        manual.parkedBaseline()
        manual.engine.setChildOnBoard(true)
        manual.depart()
        manual.park()
        manual.openClose(DoorPos.DRIVER)
        assertEquals(1, manual.cues(Cue.REAR_SEAT_CHECK).size)

        val off = Rig(EngineConfig(childMode = false))
        off.departWithChildByDoor()
        off.park()
        off.openClose(DoorPos.DRIVER)
        assertTrue(off.cues(Cue.REAR_SEAT_CHECK).isEmpty())
    }

    // ---------------- 벨트 · 급제동 ----------------

    @Test
    fun belt_unbuckledWhileDriving_notifiesAfterGrace_withCooldown() {
        val r = Rig()
        r.parkedBaseline()
        r.depart()
        r.send(TripSignal.BeltWarning(SeatPos.PASSENGER, true))
        r.advance(29 * SEC)
        assertTrue(r.notices(NoticeKind.BELT_UNBUCKLED_DRIVING).isEmpty())
        r.advance(1 * SEC)
        val n = r.notices(NoticeKind.BELT_UNBUCKLED_DRIVING).single()
        assertTrue(n.detail.contains("조수석"))
        r.advance(4 * MIN)
        assertEquals("5분 안엔 다시 안 보낸다", 1, r.notices(NoticeKind.BELT_UNBUCKLED_DRIVING).size)
        r.advance(2 * MIN)
        assertEquals(2, r.notices(NoticeKind.BELT_UNBUCKLED_DRIVING).size)
        r.send(TripSignal.BeltWarning(SeatPos.PASSENGER, false))
        r.advance(10 * MIN, step = MIN)
        assertEquals(2, r.notices(NoticeKind.BELT_UNBUCKLED_DRIVING).size)
    }

    /** 속도는 실제 센서처럼 1초마다 들어온다. 4초에 한 번씩 60→40 km/h 급제동. */
    private fun Rig.hardBrakes(times: Int) {
        send(TripSignal.Speed(60f))
        repeat(times) {
            advance(1 * SEC)
            send(TripSignal.Brake(true), TripSignal.Speed(40f))
            advance(1 * SEC)
            send(TripSignal.Brake(false), TripSignal.Speed(50f))
            advance(1 * SEC)
            send(TripSignal.Speed(60f))
            advance(1 * SEC)
            send(TripSignal.Speed(60f))
        }
    }

    @Test
    fun hardBrake_threeTimes_whenEnabled() {
        val r = Rig(EngineConfig(hardBrakeEnabled = true))
        r.parkedBaseline()
        r.depart()
        r.hardBrakes(2)
        assertTrue(r.notices(NoticeKind.HARD_BRAKE).isEmpty())
        r.hardBrakes(1)
        assertEquals(1, r.notices(NoticeKind.HARD_BRAKE).size)
        assertEquals(1, r.cues(Cue.SLOW_DOWN).size)
        assertEquals(3, r.engine.snapshot().hardBrakeCount)
    }

    @Test
    fun hardBrake_ignoredWithoutBrakePedal_orWhenDisabled() {
        val noPedal = Rig(EngineConfig(hardBrakeEnabled = true))
        noPedal.parkedBaseline()
        noPedal.depart()
        noPedal.send(TripSignal.Brake(false))
        repeat(3) {
            noPedal.send(TripSignal.Speed(60f)); noPedal.advance(SEC)
            noPedal.send(TripSignal.Speed(30f)); noPedal.advance(3 * SEC)
        }
        assertTrue("브레이크를 안 밟은 감속(언덕, 신호)은 급제동이 아니다", noPedal.notices(NoticeKind.HARD_BRAKE).isEmpty())

        val off = Rig()
        off.parkedBaseline()
        off.depart()
        off.hardBrakes(5)
        assertTrue(off.notices(NoticeKind.HARD_BRAKE).isEmpty())
    }

    // ---------------- 기어가 없을 때 ----------------

    @Test
    fun speedOnly_drivingDetection() {
        val r = Rig()
        r.send(TripSignal.Speed(30f))
        assertTrue(r.engine.snapshot().driving)
        assertEquals(DriveBasis.SPEED, r.engine.snapshot().driveBasis)
        r.send(TripSignal.Speed(0f))
        r.advance(20 * SEC)
        assertTrue("신호 대기는 주행 중", r.engine.snapshot().driving)
        r.send(TripSignal.Door(DoorPos.DRIVER, true))
        assertFalse("멈춘 채 운전석 문이 열리면 바로 정차", r.engine.snapshot().driving)
        assertEquals(1, r.notices(NoticeKind.ARRIVED).size)

        val hold = Rig()
        hold.send(TripSignal.Speed(30f))
        hold.send(TripSignal.Speed(0f))
        hold.advance(30 * SEC)
        assertFalse(hold.engine.snapshot().driving)
    }

    @Test
    fun uxRestriction_isTheLastFallback() {
        val r = Rig()
        r.send(TripSignal.UxRestricted(true))
        assertTrue(r.engine.snapshot().driving)
        assertEquals(DriveBasis.UX_RESTRICTION, r.engine.snapshot().driveBasis)
        r.send(TripSignal.Gear(parked = true))
        assertFalse("기어가 들어오면 기어를 믿는다", r.engine.snapshot().driving)
    }

    // ---------------- 위치 · 거리 ----------------

    @Test
    fun location_isThrottled_andDistanceFallsBackToPath() {
        val r = Rig()
        r.parkedBaseline()
        r.send(TripSignal.Location(37.40, 127.10))
        r.depart()
        r.clear()
        for (i in 1..10) {
            r.advance(5 * SEC)
            r.send(TripSignal.Location(37.40 + i * 0.001, 127.10))
        }
        assertEquals("50초 동안 30초 간격", 1, r.events.filterIsInstance<TripEvent.LocationUpdate>().size)
        val km = r.engine.snapshot().distanceKm
        assertNotNull(km)
        assertEquals(1.11, km!!, 0.02)
        // GPS 튐(수십 km)은 거리에 넣지 않는다.
        r.send(TripSignal.Location(38.0, 128.0))
        assertEquals(1.11, r.engine.snapshot().distanceKm!!, 0.02)
    }

    @Test
    fun cooldown_doesNotBlockEmergency() {
        val r = Rig()
        r.parkedBaseline()
        r.depart()
        r.send(TripSignal.Airbag(true))
        r.send(TripSignal.Airbag(false))
        r.send(TripSignal.Airbag(true))
        assertEquals(2, r.notices(NoticeKind.EMERGENCY_AIRBAG).size)
    }

    @Test
    fun snapshot_beforeAnything_isQuiet() {
        val r = Rig()
        val s = r.engine.snapshot()
        assertFalse(s.driving)
        assertFalse(s.tripActive)
        assertEquals(DriveBasis.NONE, s.driveBasis)
        assertNull(s.distanceKm)
        assertTrue(r.engine.tick().isEmpty())
    }
}
