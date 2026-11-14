package kr.ansimgwiga.car.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScriptTest {

    /** 대본을 앱과 같은 방식(1초마다 tick, 단계마다 시계 빨리 감기)으로 재생한다. */
    private fun play(config: EngineConfig): List<TripEvent> {
        var warp = 0L
        var realMs = 0L
        val start = 5_000_000L
        val engine = TripEngine(config) { start + realMs + warp }
        val events = mutableListOf<TripEvent>()
        val steps = DemoScript.steps()
        var i = 0
        for (sec in 0..DemoScript.lengthSec + 5) {
            realMs = sec * 1000L
            while (i < steps.size && steps[i].atSec == sec) {
                val step = steps[i++]
                warp += step.warpMs
                step.signals.forEach { events += engine.onSignal(it) }
            }
            events += engine.tick()
        }
        return events
    }

    private fun List<TripEvent>.kinds() = filterIsInstance<TripEvent.Notice>().map { it.kind }
    private fun List<TripEvent>.cueList() = filterIsInstance<TripEvent.DriverCue>().map { it.cue }

    @Test
    fun demo_withDemoConfig_showsEveryFirstStageFeature_andHardBrake() {
        val events = play(EngineConfig.demo())
        assertEquals(
            listOf(
                NoticeKind.DEPARTED,
                NoticeKind.REST_SUGGESTED,
                NoticeKind.HARD_BRAKE,
                NoticeKind.ARRIVED,
                NoticeKind.MAINTENANCE_NEEDED,
                NoticeKind.REAR_SEAT_UNCHECKED
            ),
            events.kinds()
        )
        assertEquals(
            listOf(Cue.BELT, Cue.MAINTENANCE, Cue.REST, Cue.SLOW_DOWN, Cue.REAR_SEAT_CHECK, Cue.REAR_SEAT_CHECK),
            events.cueList()
        )
        val departed = events.filterIsInstance<TripEvent.Notice>().first()
        assertTrue(departed.detail, departed.detail.contains("뒷좌석 동승 있음"))
        val arrived = events.filterIsInstance<TripEvent.Notice>().first { it.kind == NoticeKind.ARRIVED }
        assertEquals("집 도착했어요", arrived.title)
        assertTrue(arrived.detail, arrived.detail.contains("172.4 km"))
        assertTrue(arrived.detail, arrived.detail.contains("급제동 3회"))
        assertTrue(arrived.context.toString(), arrived.context.containsAll(listOf("전조등 켬", "와이퍼 작동")))
        assertTrue(events.filterIsInstance<TripEvent.LocationUpdate>().size >= 3)
    }

    @Test
    fun demo_withRealConfig_stillShowsTheStory() {
        val kinds = play(EngineConfig()).kinds()
        assertEquals(
            listOf(
                NoticeKind.DEPARTED,
                NoticeKind.REST_SUGGESTED,
                NoticeKind.ARRIVED,
                NoticeKind.MAINTENANCE_NEEDED,
                NoticeKind.REAR_SEAT_UNCHECKED
            ),
            kinds
        )
    }

    @Test
    fun demo_isShortEnoughForAPitch() {
        assertTrue(DemoScript.lengthSec in 60..120)
        assertTrue(DemoScript.steps().zipWithNext().all { (a, b) -> a.atSec <= b.atSec })
    }
}
