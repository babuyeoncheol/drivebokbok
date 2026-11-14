package ai.pleos.playground.vehicle.listener

import fake.sdk.OilLevel

// 테스트 전용: 실제 SDK 리스너와 같은 패키지·모양의 가짜.
interface TestOnlyBeltWarningListener {
    fun onSeatBeltNotBuckledWarningUpdated(value: Boolean?)
    fun onFailed(e: Exception)
}

interface TestOnlyOilStateListener {
    fun onEngineOilLevelStateUpdated(value: OilLevel?)
    fun onFailed(e: Exception)
}
