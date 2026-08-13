package com.lxseek.chat.util

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

data class TtsDiagnosticInfo(
    val initialized: Boolean,
    val available: Boolean,
    val engineName: String?,
    val availableEngines: List<String>,
    val langMissingData: Boolean,
)

/**
 * Process-scoped singleton wrapping Android [TextToSpeech] for assistant message read-aloud.
 *
 * Initialization is async and **retryable**: a failed first attempt (engine missing or
 * temporarily unavailable) does not permanently poison the singleton — later [init] calls
 * shut down the dead instance and rebuild. The first [speak] before the engine is ready is
 * buffered and flushed once [TextToSpeech.SUCCESS] arrives; on failure the buffer is cleared
 * and [speak] returns `false` so the caller can cancel its playing indicator immediately.
 *
 * Playback state is exposed as hot [StateFlow]s so the ViewModel can drive a per-message
 * playing indicator without polling.
 */
object TtsManager {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initialized = false
    // Bumped on every (re)init/shutdown so a stale callback from a torn-down instance can
    // be detected and ignored — without this, a late ERROR from the old engine could clobber
    // the `initialized`/`isAvailable` flags just set by the new engine's SUCCESS.
    @Volatile private var initGeneration = 0

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _langMissingData = MutableStateFlow(false)
    val langMissingData: StateFlow<Boolean> = _langMissingData.asStateFlow()

    @Volatile private var pendingText: String? = null
    @Volatile private var pendingLanguage: String = "system"
    @Volatile private var pendingRate: Float = 1.0f
    @Volatile private var appContext: Context? = null
    @Volatile private var audioManager: AudioManager? = null
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null

    fun init(context: Context) {
        // Already up — nothing to do.
        if (tts != null && initialized) return
        // A previous attempt left a dead instance (init callback reported ERROR, or the
        // engine was later uninstalled). Tear it down so we can rebuild from scratch.
        if (tts != null && !initialized) {
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Throwable) {
                // Best-effort teardown; ignore.
            }
            tts = null
        }
        val generation = ++initGeneration
        val appContext = context.applicationContext
        this.appContext = appContext
        tts = TextToSpeech(appContext) { status ->
            if (generation != initGeneration) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                _isAvailable.value = true
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                DebugLog.d("TtsManager", "init SUCCESS, engines: ${tts?.engines?.map { it.name }}")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { DebugLog.d("TtsManager", "onStart $utteranceId"); _isPlaying.value = true }
                    override fun onDone(utteranceId: String?) { DebugLog.d("TtsManager", "onDone $utteranceId"); _isPlaying.value = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { DebugLog.d("TtsManager", "onError $utteranceId"); _isPlaying.value = false }
                    override fun onError(utteranceId: String?, errorCode: Int) { DebugLog.d("TtsManager", "onError $utteranceId code=$errorCode"); _isPlaying.value = false }
                })
                pendingText?.let { text ->
                    pendingText = null
                    speakInternal(text, pendingLanguage, pendingRate)
                }
            } else {
                DebugLog.e("TtsManager", "init FAILED status=$status")
                initialized = false
                _isAvailable.value = false
                _isPlaying.value = false
                // Drop any buffered utterance — it can never be flushed now, and leaving it
                // pinned would make a follow-up speak() silently enqueue against a dead engine.
                pendingText = null
            }
        }
    }

    /**
     * Enqueue [text] for synthesis. Returns `true` if the utterance was handed to the engine
     * (or buffered while the engine finishes initializing); `false` if it was rejected
     * (blank text, engine missing, or [speak] reported an error). The caller should use the
     * `false` result to cancel any playing indicator it optimistically set.
     */
    fun speak(text: String, language: String = "system", rate: Float = 1.0f): Boolean {
        if (text.isBlank()) return false
        if (!initialized || tts == null) {
            // Engine still initializing — buffer and flush on SUCCESS. If init ultimately
            // fails the buffer is cleared in the ERROR branch, so this never leaks.
            pendingText = text
            pendingLanguage = language
            pendingRate = rate
            return true
        }
        return speakInternal(text, language, rate)
    }

    private fun speakInternal(text: String, language: String, rate: Float): Boolean {
        val engine = tts ?: return false
        requestAudioFocus()
        val locale = when (language) {
            "en" -> Locale.US
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.getDefault()
        }
        val langResult = engine.setLanguage(locale)
        DebugLog.d("TtsManager", "setLanguage($locale)=$langResult lang=$language")
        _langMissingData.value = (langResult == TextToSpeech.LANG_MISSING_DATA)
        if (langResult == TextToSpeech.LANG_NOT_SUPPORTED ||
            langResult == TextToSpeech.LANG_MISSING_DATA
        ) {
            val fallbackResult = engine.setLanguage(Locale.getDefault())
            DebugLog.d("TtsManager", "fallback setLanguage(default)=$fallbackResult")
            if (fallbackResult == TextToSpeech.LANG_MISSING_DATA) {
                _langMissingData.value = true
            }
            if (fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED ||
                fallbackResult == TextToSpeech.LANG_MISSING_DATA
            ) {
                engine.setLanguage(Locale.US)
            }
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        DebugLog.d("TtsManager", "speak result=$speakResult textLen=${text.length} text='${text.take(50)}'")
        if (speakResult != TextToSpeech.SUCCESS) {
            _isPlaying.value = false
            return false
        }
        return true
    }

    private fun requestAudioFocus() {
        val ctx = appContext ?: return
        val am = audioManager
            ?: (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.also { audioManager = it }
            ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    fun stop() {
        tts?.stop()
        _isPlaying.value = false
        abandonAudioFocus()
    }

    fun shutdown() {
        initGeneration++
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
        _isAvailable.value = false
        _isPlaying.value = false
        _langMissingData.value = false
        pendingText = null
        abandonAudioFocus()
    }

    fun getDiagnosticInfo(): TtsDiagnosticInfo {
        val engine = tts
        return TtsDiagnosticInfo(
            initialized = initialized,
            available = _isAvailable.value,
            engineName = engine?.defaultEngine,
            availableEngines = engine?.engines?.map { it.name } ?: emptyList(),
            langMissingData = _langMissingData.value,
        )
    }

    fun testSpeak(): Boolean {
        return speak("Hello, this is a TTS test. 你好，这是语音测试。", "system", 1.0f)
    }

    fun systemTtsSettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun installTtsDataIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)

    /**
     * Best-effort Markdown stripping for TTS: removes code spans, images, links, heading
     * markers, emphasis, blockquotes, and list bullets so only prose reaches the engine.
     */
    fun stripMarkdown(text: String): String =
        text
            .replace(Regex("`{1,3}[^`]*`{1,3}"), "")
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("#+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[*_~>|]"), "")
            .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
