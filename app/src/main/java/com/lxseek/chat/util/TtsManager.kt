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
 * Initialization is async; the first [speak] call before the engine is ready is buffered and
 * flushed once [TextToSpeech.SUCCESS] arrives. Playback state is exposed as a hot [StateFlow]
 * so the ViewModel can drive a per-message playing indicator without polling.
 */
object TtsManager {
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initialized = false

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile private var pendingText: String? = null
    @Volatile private var pendingLanguage: String = "system"
    @Volatile private var pendingRate: Float = 1.0f

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initialized = true
                _isAvailable.value = true
                pendingText?.let { text ->
                    speakInternal(text, pendingLanguage, pendingRate)
                    pendingText = null
                }
            } else {
                initialized = false
                _isAvailable.value = false
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _isPlaying.value = true }
            override fun onDone(utteranceId: String?) { _isPlaying.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _isPlaying.value = false }
            override fun onError(utteranceId: String?, errorCode: Int) { _isPlaying.value = false }
        })
    }

    fun speak(text: String, language: String = "system", rate: Float = 1.0f) {
        if (text.isBlank()) return
        if (!initialized || tts == null) {
            pendingText = text
            pendingLanguage = language
            pendingRate = rate
            return
        }
        speakInternal(text, language, rate)
    }

    private fun speakInternal(text: String, language: String, rate: Float) {
        val engine = tts ?: return
        val locale = when (language) {
            "en" -> Locale.US
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.getDefault()
        }
        engine.language = locale
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stop() {
        tts?.stop()
        _isPlaying.value = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        initialized = false
        _isAvailable.value = false
        _isPlaying.value = false
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
