package kr.ansimgwiga.car.core

/**
 * Supabase(PostgREST) 주소·헤더·응답 해석. 안드로이드 없이 테스트한다.
 *
 *  - 이벤트 올리기   POST {base}/rest/v1/events            (본문: EventJson 배열)
 *  - 응원 받기       POST {base}/rest/v1/rpc/car_cheers     {"p_code": "...", "p_after_id": 0}
 *  - 연결 확인       GET  {base}/rest/v1/
 */
object ServerApi {

    /** 사용자가 넣은 주소를 프로젝트 주소로 맞춘다. 끝의 / 와 /rest/v1/… 는 떼어 낸다. */
    fun normalizeBase(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        val rest = s.indexOf("/rest/v1")
        if (rest >= 0) s = s.substring(0, rest)
        return s.trimEnd('/')
    }

    fun eventsUrl(base: String) = "${normalizeBase(base)}/rest/v1/events"
    fun rpcUrl(base: String, function: String) = "${normalizeBase(base)}/rest/v1/rpc/$function"
    fun rootUrl(base: String) = "${normalizeBase(base)}/rest/v1/"

    /**
     * 새 키(sb_publishable_…)는 apikey 헤더에만 넣는다(JWT가 아니라 Authorization에 넣으면 거절됨).
     * 예전 anon 키(JWT)는 apikey와 Authorization: Bearer 둘 다.
     */
    fun authHeaders(key: String): Map<String, String> {
        val k = key.trim()
        if (k.isEmpty()) return emptyMap()
        return if (k.startsWith("sb_")) mapOf("apikey" to k) else mapOf("apikey" to k, "Authorization" to "Bearer $k")
    }

    fun carCheersBody(carCode: String, afterId: Long): String =
        "{\"p_code\":${EventJson.quote(carCode)},\"p_after_id\":$afterId}"

    data class Cheer(val id: Long, val preset: CheerPreset)

    /**
     * car_cheers 응답([{"id":3,"preset":"SAFE","created_at":"…"}, …])을 읽는다.
     * 모르는 프리셋은 건너뛴다. 모양이 이상하면 빈 목록.
     */
    fun parseCheers(json: String): List<Cheer> {
        val body = json.trim()
        if (!body.startsWith("[")) return emptyList()
        val objects = Regex("\\{[^{}]*\\}").findAll(body).map { it.value }
        return objects.mapNotNull { obj ->
            val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.get(1)?.toLongOrNull()
            val preset = Regex("\"preset\"\\s*:\\s*\"([A-Z_]+)\"").find(obj)?.groupValues?.get(1)
            val p = CheerPreset.of(preset)
            if (id != null && p != null) Cheer(id, p) else null
        }.sortedBy { it.id }.toList()
    }
}
