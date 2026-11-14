package kr.ansimgwiga.car.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 진단 화면의 다섯 칸. 에뮬레이터에서 확인할 것 순서 그대로. */
enum class DiagGroup(val title: String, val question: String) {
    VEHICLE("1. 차량 값 (Vehicle SDK)", "벨트 경고·뒷좌석 벨트·뒷문·에어백·정비 경고 값이 오나?"),
    NAVI("2. 내비 (NaviHelper)", "길 안내를 안 켜도 위치가 오나? 목적지·도착·남은 시간은?"),
    CAR_API("3. 속도·기어 (안드로이드 표준 API)", "권한을 허용하면 속도·기어·주행 제한 값이 오나?"),
    SERVER("4. 외부 서버 연결", "에뮬레이터에서 HTTPS로 밖에 나가나?"),
    VOICE("5. 음성 안내", "TTS 엔진이 있나? 없으면 녹음 파일·효과음으로"),
    SDK_METHODS("부록. SDK 메서드 목록", "값이 안 오면 이 목록에서 정확한 이름을 찾는다")
}

enum class DiagStatus { OK, WARN, FAIL, INFO }

data class DiagEntry(
    val group: DiagGroup,
    val key: String,
    val label: String,
    val value: String,
    val status: DiagStatus,
    val atMs: Long,
    val updates: Int
)

class DiagStore {
    private val _entries = MutableStateFlow<Map<String, DiagEntry>>(emptyMap())
    val entries: StateFlow<Map<String, DiagEntry>> = _entries.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun put(group: DiagGroup, key: String, label: String, value: String, status: DiagStatus = DiagStatus.INFO) {
        val id = "${group.name}/$key"
        _entries.update { map ->
            val prev = map[id]
            map + (id to DiagEntry(group, key, label, value, status, System.currentTimeMillis(), (prev?.updates ?: 0) + 1))
        }
    }

    fun fail(group: DiagGroup, key: String, label: String, error: Throwable) =
        put(group, key, label, "${error.javaClass.simpleName}: ${error.message ?: "-"}", DiagStatus.FAIL)

    fun line(text: String) {
        _log.update { (listOf(text) + it).take(MAX_LOG) }
    }

    fun clear(group: DiagGroup) {
        _entries.update { map -> map.filterValues { it.group != group } }
    }

    private companion object {
        const val MAX_LOG = 200
    }
}
