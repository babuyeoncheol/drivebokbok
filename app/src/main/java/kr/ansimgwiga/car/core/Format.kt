package kr.ansimgwiga.car.core

import java.util.Locale

/** 1시간 5분 / 35분 / 1분 미만 */
fun formatDuration(ms: Long): String {
    if (ms < 60_000L) {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        return if (sec == 0L) "1분 미만" else "${sec}초"
    }
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "${m}분"
        m == 0L -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}

fun formatKm(km: Double): String = String.format(Locale.US, "%.1f", km)

fun formatLatLng(p: LatLng): String = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng)
