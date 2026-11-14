package kr.ansimgwiga.car.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.ansimgwiga.car.core.CheerPreset
import kr.ansimgwiga.car.core.ServerApi

/**
 * 가족이 보낸 응원 메시지를 10초마다 받아 온다(서버 함수 car_cheers).
 * 서버는 2분 안의 메시지만 돌려주므로, 앱을 늦게 켜도 지난 메시지가 한꺼번에 울리지 않는다.
 */
class CheerPoller(
    private val scope: CoroutineScope,
    private val serverUrl: () -> String,
    private val apiKey: () -> String,
    private val carCode: () -> String,
    private val lastId: () -> Long,
    private val saveLastId: (Long) -> Unit,
    private val onCheer: (CheerPreset) -> Unit,
    private val onStatus: (ok: Boolean, text: String) -> Unit
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                pollOnce()
                delay(INTERVAL_MS)
            }
        }
    }

    /** 한 번 받아 오기. 진단 화면 버튼에서도 부른다. */
    suspend fun pollOnce() {
        val base = serverUrl()
        if (base.isBlank()) {
            onStatus(false, "서버 주소 없음")
            return
        }
        val r = withContext(Dispatchers.IO) {
            Http.request("POST", ServerApi.rpcUrl(base, "car_cheers"), apiKey(), ServerApi.carCheersBody(carCode(), lastId()))
        }
        if (!r.ok) {
            onStatus(false, r.summary())
            return
        }
        val cheers = ServerApi.parseCheers(r.body)
        cheers.forEach { cheer ->
            saveLastId(cheer.id)
            onCheer(cheer.preset)
        }
        onStatus(true, if (cheers.isEmpty()) "새 메시지 없음" else "${cheers.size}개 받음")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val INTERVAL_MS = 10_000L
    }
}
