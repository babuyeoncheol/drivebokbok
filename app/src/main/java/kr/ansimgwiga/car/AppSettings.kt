package kr.ansimgwiga.car

import android.content.Context
import kr.ansimgwiga.car.core.ServerApi
import java.security.SecureRandom

/**
 * 앱 설정. 실명·연락처는 저장하지 않는다.
 * 가족 앱과는 '차 코드'(무작위 6자리)로만 짝을 맺는다.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ansim_settings", Context.MODE_PRIVATE)

    /**
     * Supabase 프로젝트 주소. 예) https://abcd.supabase.co
     * 설정 탭에서 넣거나, PC의 local.properties에 ansim.serverUrl=… 로 넣고 빌드하면 기본값이 된다.
     */
    var serverUrl: String
        get() = ServerApi.normalizeBase(prefs.getString(KEY_URL, null) ?: BuildConfig.DEFAULT_SERVER_URL)
        set(v) = prefs.edit().putString(KEY_URL, ServerApi.normalizeBase(v)).apply()

    /** 공개 키(sb_publishable_… 또는 예전 anon 키). local.properties의 ansim.apiKey가 기본값. */
    var apiKey: String
        get() = prefs.getString(KEY_API, null) ?: BuildConfig.DEFAULT_API_KEY
        set(v) = prefs.edit().putString(KEY_API, v.trim()).apply()

    /** 마지막으로 받은 응원 메시지 번호(같은 메시지를 두 번 틀지 않게). */
    var lastCheerId: Long
        get() = prefs.getLong(KEY_CHEER, 0L)
        set(v) = prefs.edit().putLong(KEY_CHEER, v).apply()

    val carCode: String
        get() = prefs.getString(KEY_CODE, null) ?: newCode().also { prefs.edit().putString(KEY_CODE, it).apply() }

    fun regenerateCarCode(): String = newCode().also { prefs.edit().putString(KEY_CODE, it).apply() }

    /** 시연용 짧은 시간(휴식 2분, 뒷좌석 20초 …). 실제 기준은 끈다. */
    var demoTiming: Boolean
        get() = prefs.getBoolean(KEY_DEMO, true)
        set(v) = prefs.edit().putBoolean(KEY_DEMO, v).apply()

    var childMode: Boolean
        get() = prefs.getBoolean(KEY_CHILD, true)
        set(v) = prefs.edit().putBoolean(KEY_CHILD, v).apply()

    /** 2차 기능: 급제동 케어. */
    var hardBrake: Boolean
        get() = prefs.getBoolean(KEY_BRAKE, true)
        set(v) = prefs.edit().putBoolean(KEY_BRAKE, v).apply()

    private fun newCode(): String {
        val rnd = SecureRandom()
        return (1..6).map { CODE_CHARS[rnd.nextInt(CODE_CHARS.length)] }.joinToString("")
    }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_API = "api_key"
        const val KEY_CODE = "car_code"
        const val KEY_DEMO = "demo_timing"
        const val KEY_CHILD = "child_mode"
        const val KEY_BRAKE = "hard_brake"
        const val KEY_CHEER = "last_cheer_id"
        // 헷갈리는 글자(0/O, 1/I) 뺌
        const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
