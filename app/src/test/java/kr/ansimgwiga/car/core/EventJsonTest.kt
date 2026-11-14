package kr.ansimgwiga.car.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventJsonTest {

    @Test
    fun notice_hasAllColumns_andEscapesText() {
        val json = EventJson.encode(
            "AB12CD",
            TripEvent.Notice(
                NoticeKind.REAR_SEAT_UNCHECKED, 1_700_000_000_000L,
                "뒷좌석 \"확인\"", "줄\n바꿈", listOf("와이퍼 작동"), LatLng(37.4, 127.1)
            )
        )!!
        assertTrue(json, json.startsWith("{\"car_code\":\"AB12CD\",\"type\":\"notice\""))
        assertTrue(json, json.contains("\"kind\":\"REAR_SEAT_UNCHECKED\""))
        assertTrue(json, json.contains("\"level\":\"CAUTION\""))
        assertTrue(json, json.contains("\"title\":\"뒷좌석 \\\"확인\\\"\""))
        assertTrue(json, json.contains("\"detail\":\"줄\\n바꿈\""))
        assertTrue(json, json.contains("\"context\":[\"와이퍼 작동\"]"))
        assertTrue(json, json.contains("\"lat\":37.4000000"))
        assertTrue(json, json.contains("\"occurred_at_ms\":1700000000000"))
    }

    @Test
    fun location_andCues() {
        val json = EventJson.encode("X", TripEvent.LocationUpdate(5L, LatLng(1.0, 2.0), null))!!
        assertTrue(json, json.contains("\"type\":\"location\""))
        assertTrue(json, json.contains("\"destination\":null"))
        assertTrue(json, json.contains("\"context\":[]"))
        assertNull(EventJson.encode("X", TripEvent.DriverCue(Cue.REST, 1L)))
        assertNull(EventJson.encode("X", TripEvent.DrivingChanged(1L, true)))
    }

    @Test
    fun controlCharacters_andArray() {
        assertEquals("\"a\\u0001b\"", EventJson.quote("a\u0001b"))
        assertEquals("[{},{}]", EventJson.array(listOf("{}", "{}")))
    }

    @Test
    fun durations() {
        assertEquals("1분 미만", formatDuration(0))
        assertEquals("20초", formatDuration(20_000))
        assertEquals("35분", formatDuration(35 * 60_000L))
        assertEquals("2시간", formatDuration(120 * 60_000L))
        assertEquals("2시간 5분", formatDuration(125 * 60_000L + 45_000))
    }
}
