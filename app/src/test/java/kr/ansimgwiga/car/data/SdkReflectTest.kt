package kr.ansimgwiga.car.data

import fake.sdk.Coord
import fake.sdk.FakeDestinationInfo
import fake.sdk.FakeLocationInfo
import fake.sdk.FakeSafety
import fake.sdk.FakeSafetyImpl
import fake.sdk.OilLevel
import fake.sdk.SeatValue
import kr.ansimgwiga.car.core.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkReflectTest {

    private val impl = FakeSafetyImpl()
    private val domain = SdkDomain("Safety", FakeSafety::class, impl)
    private val errors = mutableListOf<String>()
    private val calls = SdkCalls { where, e -> errors += "$where: ${e.message}" }
    private val read = listOf("getCurrent", "get", "is", "has")

    @Test
    fun findsMethodsByName_skippingCapabilityChecks() {
        assertEquals("hasSeatBeltNotBuckledWarning", domain.find(read) { it.contains("NotBuckled") }?.name)
        assertEquals("registerSeatBeltNotBuckledWarning", domain.find(listOf("register")) { it.contains("NotBuckled") }?.name)
        assertNull(domain.find(read) { it.contains("Airbag") })
        assertTrue(domain.methodNames().contains("getEngineOilLevelState"))
    }

    @Test
    fun readsPerArea_withCallbacks() {
        val fn = domain.find(read) { it.contains("NotBuckled") }!!
        val areas = calls.areaNames(fn)!!
        assertEquals(SeatValue.entries.map { it.name }, areas)
        val got = mutableMapOf<String, Any?>()
        areas.forEach { a -> calls.read(domain, fn, a) { v -> got[a] = v } }
        assertEquals(true, got["ROW_2_LEFT"])
        assertEquals(false, got["ROW_1_LEFT"])
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun registersListenerProxy_andUnregistersOnRelease() {
        val fn = domain.find(listOf("register")) { it.contains("NotBuckled") }!!
        val got = mutableListOf<Any?>()
        calls.register(domain, fn, "ROW_2_RIGHT") { got += it }
        impl.beltListeners.getValue(SeatValue.ROW_2_RIGHT).onSeatBeltNotBuckledWarningUpdated(true)
        assertEquals(listOf<Any?>(true), got)

        val oilReg = domain.find(listOf("register")) { it.contains("EngineOil") }!!
        calls.register(domain, oilReg, null) { got += it }
        impl.oilListener!!.onEngineOilLevelStateUpdated(OilLevel.NORMAL)
        assertEquals(OilLevel.NORMAL, got.last())

        impl.oilListener!!.onFailed(IllegalStateException("boom"))
        assertTrue(errors.single().contains("boom"))

        calls.release()
        assertEquals(2, impl.unregisterCalls)
        assertTrue(impl.beltListeners.isEmpty())
    }

    @Test
    fun syncReturn_optionalParams_failures_andUnsupportedParams() {
        val got = mutableListOf<Any?>()
        calls.read(domain, domain.find(read) { it.contains("EngineOil") }!!, null) { got += it }
        calls.read(domain, domain.find(read) { it.contains("BrakeFluid") }!!, null) { got += it }
        assertEquals(listOf<Any?>(OilLevel.LOW, 1), got)

        calls.read(domain, domain.find(read) { it.contains("Broken") }!!, null) { got += it }
        assertTrue(errors.toString(), errors.any { it.contains("not supported") })
        calls.read(domain, domain.find(read) { it.contains("Weird") }!!, null) { got += it }
        assertTrue(errors.toString(), errors.any { it.contains("자동으로 못 채움") })
        calls.read(domain, domain.find(read) { it.contains("NotBuckled") }!!, "ROW_9_LEFT") { got += it }
        assertTrue(errors.toString(), errors.any { it.contains("ROW_9_LEFT 없음") })
        assertEquals(2, got.size)
    }

    @Test
    fun objectFields_findLatLngAndNames() {
        val loc = FakeLocationInfo("판교역로", Coord(37.39, 127.11))
        assertEquals(LatLng(37.39, 127.11), ObjectFields.latLng(loc))
        val dest = FakeDestinationInfo("집", 37.40, 127.10)
        assertEquals(LatLng(37.40, 127.10), ObjectFields.latLng(dest))
        assertEquals("집", ObjectFields.placeName(dest))
        assertNull(ObjectFields.latLng(FakeDestinationInfo("", 0.0, 0.0)))
        assertNull(ObjectFields.placeName(FakeDestinationInfo("", 0.0, 0.0)))
        assertNull(ObjectFields.latLng(null))
    }
}
