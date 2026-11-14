package kr.ansimgwiga.car.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import kr.ansimgwiga.car.core.Cue
import kr.ansimgwiga.car.diag.DiagGroup
import kr.ansimgwiga.car.diag.DiagStatus
import kr.ansimgwiga.car.diag.DiagStore
import java.util.Locale

/**
 * 운전자 안내음. 순서대로 시도한다.
 *  1. res/raw에 녹음 파일(cue_rest.ogg 등)이 있으면 그것
 *  2. 차에 한국어 TTS 엔진이 있으면 TTS (Workspace 목록엔 TTS SDK가 없어서 안드로이드 기본 엔진을 써 본다)
 *  3. 둘 다 없으면 효과음 (글자는 화면 배너로 항상 나온다)
 */
class VoiceGuide(private val context: Context, private val diag: DiagStore) : TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var tone: ToneGenerator? = null

    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun start() {
        diag.put(DiagGroup.VOICE, "tts", "TTS 엔진", "확인 중…")
        tts = try {
            TextToSpeech(context.applicationContext, this)
        } catch (t: Throwable) {
            diag.fail(DiagGroup.VOICE, "tts", "TTS 엔진", t)
            null
        }
        val clips = Cue.entries.count { clipId(it) != 0 }
        diag.put(
            DiagGroup.VOICE, "clips", "녹음 파일(res/raw)", "${clips}/${Cue.entries.size}개",
            if (clips == Cue.entries.size) DiagStatus.OK else DiagStatus.INFO
        )
    }

    override fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ttsReady = false
            diag.put(DiagGroup.VOICE, "tts", "TTS 엔진", "없음(상태 $status) → 녹음 파일·효과음으로 대신", DiagStatus.WARN)
            return
        }
        val engines = runCatching { engine.engines.joinToString { it.name } }.getOrDefault("?")
        val korean = runCatching { engine.isLanguageAvailable(Locale.KOREAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        ttsReady = korean >= TextToSpeech.LANG_AVAILABLE
        if (ttsReady) {
            engine.setLanguage(Locale.KOREAN)
            engine.setAudioAttributes(speechAttributes)
        }
        diag.put(
            DiagGroup.VOICE, "tts", "TTS 엔진",
            "엔진: ${engines.ifBlank { "(없음)" }} · 한국어 ${if (ttsReady) "가능" else "불가(코드 $korean)"}",
            if (ttsReady) DiagStatus.OK else DiagStatus.WARN
        )
    }

    fun play(cue: Cue) {
        main.post {
            val clip = clipId(cue)
            val how = when {
                clip != 0 && playClip(clip) -> "녹음 파일"
                ttsReady && tts?.speak(cue.text, TextToSpeech.QUEUE_ADD, null, cue.name) == TextToSpeech.SUCCESS -> "TTS"
                else -> { chime(); "효과음" }
            }
            diag.put(DiagGroup.VOICE, "last", "마지막 안내", "${cue.text} · $how", DiagStatus.OK)
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        runCatching { tts?.shutdown() }
        runCatching { tone?.release() }
        tts = null
        tone = null
    }

    @SuppressLint("DiscouragedApi")
    private fun clipId(cue: Cue): Int =
        context.resources.getIdentifier(cue.clipName, "raw", context.packageName)

    private fun playClip(resId: Int): Boolean = runCatching {
        val player = MediaPlayer.create(context, resId, speechAttributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
            ?: return false
        player.setOnCompletionListener { it.release() }
        player.start()
        true
    }.getOrDefault(false)

    private fun chime() {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
        }
    }
}
