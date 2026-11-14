package kr.ansimgwiga.car.sim

import kr.ansimgwiga.car.core.DoorPos
import kr.ansimgwiga.car.core.Maintenance
import kr.ansimgwiga.car.core.NoticeKind
import kr.ansimgwiga.car.core.TripEngine
import kr.ansimgwiga.car.core.TripEvent
import kr.ansimgwiga.car.core.TripSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimStateTest {

    @Test
    fun apply_tracksSwitches() {
        var s = SimState()
        s = s.apply(TripSignal.Door(DoorPos.REAR_LEFT, true))
        s = s.apply(TripSignal.MaintenanceWarning(Maintenance.ENGINE_OIL, true))
        s = s.apply(TripSignal.Gear(parked = false))
        assertTrue(s.doorOpen(DoorPos.REAR_LEFT))
        assertFalse(s.doorOpen(DoorPos.DRIVER))
        assertEquals(setOf(Maintenance.ENGINE_OIL), s.maintenance)
        assertFalse(s.gearParked)
        s = s.apply(TripSignal.MaintenanceWarning(Maintenance.ENGINE_OIL, false))
        assertTrue(s.maintenance.isEmpty())
    }

    @Test
    fun baseline_ofParkedCar_isQuiet() {
        var now = 0L
        val engine = TripEngine { now }
        val events = SimState().baseline().flatMap { engine.onSignal(it) }
        assertTrue(events.toString(), events.isEmpty())
        now += 60_000
        assertTrue(engine.tick().isEmpty())
    }

    @Test
    fun baseline_ofMovingCar_startsTrip() {
        val engine = TripEngine { 0L }
        val events = SimState(gearParked = false, speedKmh = 40f).baseline().flatMap { engine.onSignal(it) }
        assertEquals(listOf(NoticeKind.DEPARTED), events.filterIsInstance<TripEvent.Notice>().map { it.kind })
    }
}
