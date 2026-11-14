package fake.sdk

import ai.pleos.playground.vehicle.listener.TestOnlyOilStateListener
import ai.pleos.playground.vehicle.listener.TestOnlyBeltWarningListener

// 테스트 전용: Vehicle SDK의 Safety 도메인을 흉내 낸 가짜. 매개변수 모양을 일부러 섞어 놓았다.
enum class SeatValue { ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_RIGHT }
// 실제 SDK와 같은 모양: SeatArea(vararg SeatValue)
class SeatArea(vararg val values: SeatValue) {
    val value: SeatValue get() = values.single()
}
enum class OilLevel { NORMAL, LOW }

interface FakeSafety {
    fun hasSeatBeltNotBuckledWarning(area: SeatArea, onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit)
    fun registerSeatBeltNotBuckledWarning(area: SeatArea, listener: TestOnlyBeltWarningListener): Boolean
    fun unregisterSeatBeltNotBuckledWarning(area: SeatArea, listener: TestOnlyBeltWarningListener): Boolean
    fun checkSeatBeltNotBuckledWarningCapabilities(onComplete: (Any?, Any?) -> Unit, onFailed: (Exception) -> Unit)
    fun getEngineOilLevelState(onComplete: (OilLevel?) -> Unit, onFailed: (Exception) -> Unit = {})
    fun registerEngineOilLevelState(listener: TestOnlyOilStateListener)
    fun unregisterEngineOilLevelState(listener: TestOnlyOilStateListener)
    fun getBrakeFluidLevelState(): Int
    fun getBrokenThing(onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit)
    fun getWeird(x: StringBuilder): Int
}

class FakeSafetyImpl : FakeSafety {
    val beltListeners = mutableMapOf<SeatValue, TestOnlyBeltWarningListener>()
    var oilListener: TestOnlyOilStateListener? = null
    var unregisterCalls = 0

    override fun hasSeatBeltNotBuckledWarning(area: SeatArea, onComplete: (Boolean?) -> Unit, onFailed: (Exception) -> Unit) =
        onComplete(area.value == SeatValue.ROW_2_LEFT)

    override fun registerSeatBeltNotBuckledWarning(area: SeatArea, listener: TestOnlyBeltWarningListener): Boolean {
        beltListeners[area.value] = listener
        return true
    }

    override fun unregisterSeatBeltNotBuckledWarning(area: SeatArea, listener: TestOnlyBeltWarningListener): Boolean {
        if (beltListeners[area.value] === listener) beltListeners.remove(area.value)
        unregisterCalls++
        return true
    }

    override fun checkSeatBeltNotBuckledWarningCapabilities(onComplete: (Any?, Any?) -> Unit, onFailed: (Exception) -> Unit) = Unit
    override fun getEngineOilLevelState(onComplete: (OilLevel?) -> Unit, onFailed: (Exception) -> Unit) = onComplete(OilLevel.LOW)
    override fun registerEngineOilLevelState(listener: TestOnlyOilStateListener) { oilListener = listener }
    override fun unregisterEngineOilLevelState(listener: TestOnlyOilStateListener) { oilListener = null; unregisterCalls++ }
    override fun getBrakeFluidLevelState(): Int = 1
    override fun getBrokenThing(onComplete: (Int?) -> Unit, onFailed: (Exception) -> Unit) = onFailed(IllegalStateException("not supported"))
    override fun getWeird(x: StringBuilder): Int = 0
}

// 내비 데이터 흉내
data class Coord(val latitude: Double, val longitude: Double)
data class FakeLocationInfo(val roadName: String, val coord: Coord)
data class FakeDestinationInfo(val poiName: String, val lat: Double, val lon: Double)
