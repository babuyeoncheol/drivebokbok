package kr.ansimgwiga.car.net

import kr.ansimgwiga.car.core.ServerApi
import java.net.HttpURLConnection
import java.net.URL

/** 서버 응답 한 번. ok = 2xx. body는 앞부분만. */
data class HttpResult(val code: Int, val body: String, val error: String? = null) {
    val ok: Boolean get() = code in 200..299
    fun summary(): String = error ?: if (ok) "HTTP $code" else "HTTP $code ${body.take(200)}"
}

internal object Http {
    private const val TIMEOUT_MS = 8_000

    fun request(method: String, url: String, key: String, body: String? = null, prefer: String? = null): HttpResult = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            ServerApi.authHeaders(key).forEach { (k, v) -> setRequestProperty(k, v) }
            if (prefer != null) setRequestProperty("Prefer", prefer)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = runCatching { stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull().orEmpty()
        conn.disconnect()
        HttpResult(code, text.take(20_000))
    } catch (t: Throwable) {
        HttpResult(-1, "", "${t.javaClass.simpleName}: ${t.message}")
    }
}
