package kr.ansimgwiga.car.core

import java.util.Locale

/**
 * 서버로 보낼 JSON. 안드로이드 org.json 없이 만들어서 JVM 테스트로 검증한다.
 *
 * 한 줄 = 서버 events 테이블의 한 행.
 *   car_code     가족 앱과 짝을 맺는 코드(실명·연락처 아님)
 *   type         "notice" | "location"
 *   kind, level  알림 종류와 단계(INFO / CAUTION / EMERGENCY)
 *   title, detail, context
 *   lat, lng, destination
 *   occurred_at_ms  차에서 일이 생긴 시각(밀리초)
 */
object EventJson {

    /** 서버로 보낼 이벤트만 JSON으로. 운전자 안내음 같은 차 안 이벤트는 null. */
    fun encode(carCode: String, event: TripEvent): String? = when (event) {
        is TripEvent.Notice -> obj(
            "car_code" to carCode,
            "type" to "notice",
            "kind" to event.kind.name,
            "level" to event.kind.level.name,
            "title" to event.title,
            "detail" to event.detail,
            "context" to event.context,
            "lat" to event.location?.lat,
            "lng" to event.location?.lng,
            "destination" to null,
            "occurred_at_ms" to event.atMs
        )
        is TripEvent.LocationUpdate -> obj(
            "car_code" to carCode,
            "type" to "location",
            "kind" to null,
            "level" to null,
            "title" to null,
            "detail" to null,
            "context" to emptyList<String>(),
            "lat" to event.location.lat,
            "lng" to event.location.lng,
            "destination" to event.destination,
            "occurred_at_ms" to event.atMs
        )
        is TripEvent.DriverCue, is TripEvent.DrivingChanged -> null
    }

    /** 여러 행을 배열 하나로(한 번에 올리기). */
    fun array(rows: List<String>): String = rows.joinToString(prefix = "[", postfix = "]", separator = ",")

    private fun obj(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) -> "${quote(k)}:${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean -> v.toString()
        is Double -> if (v.isFinite()) String.format(Locale.US, "%.7f", v) else "null"
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Number -> v.toString()
        is List<*> -> v.joinToString(prefix = "[", postfix = "]", separator = ",") { value(it) }
        else -> quote(v.toString())
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format(Locale.US, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
