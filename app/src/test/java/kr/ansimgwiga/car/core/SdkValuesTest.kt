package kr.ansimgwiga.car.core

import kr.ansimgwiga.car.core.SdkValues.asBool
import kr.ansimgwiga.car.core.SdkValues.doorPos
import kr.ansimgwiga.car.core.SdkValues.seatPos
import kr.ansimgwiga.car.core.SdkValues.wiperOn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdkValuesTest {

    private enum class Level { NORMAL, LOW, ABNORMAL, UNKNOWN }
    private enum class Buckle { BUCKLED, UNBUCKLED, NOT_BUCKLED }
    private enum class Wiper { OFF, LOW, HIGH, AUTO, INTERMITTENT, UNKNOWN }

    @Test
    fun booleans_numbers_enums_strings() {
        assertEquals(true, asBool(true))
        assertEquals(false, asBool(0))
        assertEquals(true, asBool(1))
        assertEquals(false, asBool(Level.NORMAL))
        assertEquals(true, asBool(Level.LOW))
        assertEquals(true, asBool(Level.ABNORMAL))
        assertNull(asBool(Level.UNKNOWN))
        assertEquals(true, asBool(Buckle.BUCKLED))
        assertEquals(false, asBool(Buckle.UNBUCKLED))
        assertEquals(false, asBool(Buckle.NOT_BUCKLED))
        assertEquals(true, asBool("true"))
        assertEquals(false, asBool("OFF"))
        assertEquals(true, asBool("ON"))
        assertEquals(true, asBool(listOf(false, true)))
        assertEquals(false, asBool(listOf(false, false)))
        assertNull(asBool(Any()))
        assertNull(asBool(emptyList<Boolean>()))
    }

    @Test
    fun wiper() {
        assertEquals(false, wiperOn(Wiper.OFF))
        assertEquals(true, wiperOn(Wiper.LOW))
        assertEquals(true, wiperOn(Wiper.INTERMITTENT))
        assertNull(wiperOn(Wiper.UNKNOWN))
        assertEquals(true, wiperOn(2))
    }

    @Test
    fun areas_leftHandDrive() {
        assertEquals(SeatPos.DRIVER, seatPos("ROW_1_LEFT"))
        assertEquals(SeatPos.PASSENGER, seatPos("ROW_1_RIGHT"))
        assertEquals(SeatPos.REAR_LEFT, seatPos("ROW_2_LEFT"))
        assertEquals(SeatPos.REAR_CENTER, seatPos("ROW_2_CENTER"))
        assertEquals(SeatPos.REAR_RIGHT, seatPos("ROW_3_RIGHT"))
        assertNull(seatPos("HOOD"))
        assertEquals(DoorPos.REAR_RIGHT, doorPos("ROW_2_RIGHT"))
        assertNull(doorPos("REAR"))
        assertNull(doorPos(null))
    }

    @Test
    fun describe_stripsObjectIds() {
        assertEquals("CapabilityConfig", SdkValues.describe("ai.pleos.x.CapabilityConfig@1a2b3c"))
        assertEquals("[1, 2]", SdkValues.describe(intArrayOf(1, 2)))
        assertEquals("LOW", SdkValues.describe(Level.LOW))
    }
}
