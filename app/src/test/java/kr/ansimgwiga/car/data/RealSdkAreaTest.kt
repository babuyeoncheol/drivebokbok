package kr.ansimgwiga.car.data

import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실제 Vehicle SDK의 영역 클래스로 확인한다. SeatArea·WindowArea는 `vararg` 생성자라
 * 매개변수가 enum 배열로 보인다(에뮬레이터 진단에서 '영역 필요'로 실패했던 원인).
 */
class RealSdkAreaTest {

    /** Safety.hasSeatBeltNotBuckledWarning, Wiper.getCurrentWiperState와 같은 모양. */
    interface RealShapedApi {
        fun hasSeatBeltNotBuckledWarning(area: SeatArea, onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)
        fun getCurrentWiperState(area: WindowArea, onComplete: (Any?) -> Unit, onFailed: (Exception) -> Unit)
    }

    private val seen = mutableListOf<Any>()
    private val impl = object : RealShapedApi {
        override fun hasSeatBeltNotBuckledWarning(area: SeatArea, onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit) {
            seen += area
            onComplete(area.values.single() == SeatValue.ROW_2_LEFT)
        }
        override fun getCurrentWiperState(area: WindowArea, onComplete: (Any?) -> Unit, onFailed: (Exception) -> Unit) {
            seen += area
            onComplete("ON")
        }
    }
    private val domain = SdkDomain("Real", RealShapedApi::class, impl)
    private val errors = mutableListOf<String>()
    private val calls = SdkCalls { where, e -> errors += "$where: ${e.message}" }

    @Test
    fun buildsVarargSeatArea_forEveryRealSeatValue() {
        val fn = domain.find(listOf("has")) { it.contains("NotBuckled") }!!
        val areas = calls.areaNames(fn)!!
        assertEquals(SeatValue.entries.map { it.name }, areas)

        val got = mutableMapOf<String, Any?>()
        areas.forEach { a -> calls.read(domain, fn, a) { v -> got[a] = v } }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(true, got["ROW_2_LEFT"])
        assertEquals(false, got["ROW_1_LEFT"])
        assertArrayEquals(arrayOf(SeatValue.ROW_1_LEFT), (seen.first() as SeatArea).values)
    }

    @Test
    fun buildsVarargWindowArea_forWiper() {
        val fn = domain.find(listOf("getCurrent")) { it.contains("WiperState") }!!
        assertTrue(calls.areaNames(fn)!!.contains("FRONT_WINDSHIELD"))
        var got: Any? = null
        calls.read(domain, fn, "FRONT_WINDSHIELD") { got = it }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals("ON", got)
        assertArrayEquals(arrayOf(WindowValue.FRONT_WINDSHIELD), (seen.single() as WindowArea).values)
    }
}
