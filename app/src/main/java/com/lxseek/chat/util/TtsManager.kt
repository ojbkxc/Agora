package com.lxseek.chat.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

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

    @Volatile private var pendingText: String? = null
    @Volatile private var pendingLanguage: String = "system"
    @Volatile private var pendingRate: Float = 1.0f

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
        tts = TextToSpeech(appContext) { status ->
            if (generation != initGeneration) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                _isAvailable.value = true
                // Bind the progress listener only after the engine is ready; setting it
                // before the init callback is unreliable on some vendor ROMs and leaves
                // isPlaying stuck.
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _isPlaying.value = true }
                    override fun onDone(utteranceId: String?) { _isPlaying.value = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { _isPlaying.value = false }
                    override fun onError(utteranceId: String?, errorCode: Int) { _isPlaying.value = false }
                })
                pendingText?.let { text ->
                    pendingText = null
                    speakInternal(text, pendingLanguage, pendingRate)
                }
            } else {
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
        val locale = when (language) {
            "en" -> Locale.US
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.getDefault()
        }
        // setLanguage may report LANG_MISSING_DATA / LANG_NOT_SUPPORTED on devices without
        // the matching voice data; fall back to the platform default rather than stay silent.
        val langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_NOT_SUPPORTED ||
            langResult == TextToSpeech.LANG_MISSING_DATA
        ) {
            engine.setLanguage(Locale.getDefault())
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        if (speakResult != TextToSpeech.SUCCESS) {
            _isPlaying.value = false
            return false
        }
        return true
    }

    fun stop() {
        tts?.stop()
        _isPlaying.value = false
    }

    fun shutdown() {
        initGeneration++
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
        _isAvailable.value = false
        _isPlaying.value = false
        pendingText = null
    }

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
