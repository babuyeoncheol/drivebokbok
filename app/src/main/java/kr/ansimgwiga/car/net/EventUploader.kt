package kr.ansimgwiga.car.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.ansimgwiga.car.core.EventJson
import kr.ansimgwiga.car.core.ServerApi
import java.util.concurrent.atomic.AtomicLong

data class UploadStatus(
    val pending: Int = 0,
    val sent: Int = 0,
    val lastError: String? = null,
    val lastOkAtMs: Long? = null,
    val configured: Boolean = false
)

data class HttpCheck(val ok: Boolean, val text: String)

/**
 * 가족 알림을 서버로 올린다. 끊겨도 잃지 않게 메모리 큐에 쌓아 두고, 다시 되면 순서대로 보낸다.
 * 서버 주소 = Supabase 프로젝트 주소(https://<프로젝트>.supabase.co). /rest/v1/events로 20개씩 올린다.
 * 키 헤더는 [ServerApi.authHeaders] 규칙(새 publishable 키는 apikey만, 예전 anon 키는 둘 다).
 */
class EventUploader(
    private val scope: CoroutineScope,
    private val serverUrl: () -> String,
    private val apiKey: () -> String
) {
    private data class Row(val id: Long, val json: String)

    private val queue = ArrayDeque<Row>()
    private val ids = AtomicLong()
    private var job: Job? = null

    private val _status = MutableStateFlow(UploadStatus())
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    fun enqueue(json: String) {
        synchronized(queue) {
            queue.addLast(Row(ids.incrementAndGet(), json))
            while (queue.size > MAX_QUEUE) queue.removeFirst()
        }
        kick()
    }

    /** 설정을 바꿨거나 네트워크가 돌아왔을 때 다시 보내기. */
    fun kick() {
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch(Dispatchers.IO) { flushLoop() }
        }
    }

    private suspend fun flushLoop() {
        var backoff = 2_000L
        while (true) {
            val url = serverUrl()
            if (url.isBlank()) {
                report(error = "서버 주소가 없어요(설정 탭). 알림은 쌓아 두는 중")
                return
            }
            val batch = synchronized(queue) { queue.take(BATCH) }
            if (batch.isEmpty()) {
                report(error = null)
                return
            }
            val result = post(url, apiKey(), EventJson.array(batch.map { it.json }))
            if (result.ok) {
                val sentIds = batch.map { it.id }.toSet()
                synchronized(queue) { queue.removeAll { it.id in sentIds } }
                _status.update { it.copy(sent = it.sent + batch.size, lastOkAtMs = System.currentTimeMillis()) }
                backoff = 2_000L
            } else {
                report(error = result.text)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun report(error: String?) {
        val pending = synchronized(queue) { queue.size }
        _status.update { it.copy(pending = pending, lastError = error, configured = serverUrl().isNotBlank()) }
    }

    private fun post(base: String, key: String, body: String): HttpCheck {
        val r = Http.request("POST", ServerApi.eventsUrl(base), key, body, prefer = "return=minimal")
        return HttpCheck(r.ok, r.summary())
    }

    companion object {
        private const val MAX_QUEUE = 500
        private const val BATCH = 20

        /** 진단 4번: 밖으로 나가는지. 주소가 비면 구글 연결 확인 주소로, 있으면 우리 서버의 REST 입구로. */
        suspend fun check(base: String, key: String): HttpCheck = withContext(Dispatchers.IO) {
            val target = if (base.isBlank()) "https://www.gstatic.com/generate_204" else ServerApi.rootUrl(base)
            val started = System.currentTimeMillis()
            val r = Http.request("GET", target, if (base.isBlank()) "" else key)
            val ms = System.currentTimeMillis() - started
            // 서버까지 닿았으면(401·404 포함) 네트워크는 된다는 뜻. 401이면 키를 확인.
            val note = when {
                r.error != null -> r.error
                r.code == 401 -> "HTTP 401 · ${ms}ms (닿았지만 키가 틀렸어요)"
                else -> "HTTP ${r.code} · ${ms}ms"
            }
            HttpCheck(r.code in 200..499, "$target → $note")
        }
    }
}
