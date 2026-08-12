package com.lxseek.chat.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped singleton wrapping Android [SpeechRecognizer] for continuous voice conversation.
 *
 * Initialization is async and retryable. Recognition results are delivered via [onResult];
 * partial transcripts flow through [partialText]. The caller is responsible for transitioning
 * its own state machine (IDLE → LISTENING → PROCESSING → …) based on these signals.
 */
object SttManager {
    @Volatile private var recognizer: SpeechRecognizer? = null
    @Volatile private var initialized = false
    @Volatile private var initializing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    @Volatile private var resultCallback: ((String) -> Unit)? = null
    @Volatile private var errorCallback: ((Int) -> Unit)? = null

    fun isSupported(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context.applicationContext)

    fun init(context: Context) {
        if (initialized || initializing) return
        if (recognizer != null && !initialized) {
            try {
                recognizer?.stop()
                recognizer?.destroy()
            } catch (_: Throwable) {}
            recognizer = null
        }
        initializing = true
        val appContext = context.applicationContext
        mainHandler.post {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                initialized = true
                initializing = false
                _isAvailable.value = true
            } catch (_: Throwable) {
                initialized = false
                initializing = false
                _isAvailable.value = false
            }
        }
    }

    fun startListening(
        context: Context,
        language: String = "system",
        onResult: (String) -> Unit,
        onError: (Int) -> Unit,
    ) {
        resultCallback = onResult
        errorCallback = onError
        if (!initialized || recognizer == null) {
            init(context)
            mainHandler.postDelayed({ doStartListening(language) }, 500)
            return
        }
        doStartListening(language)
    }

    private fun doStartListening(language: String) {
        val engine = recognizer ?: run { errorCallback?.invoke(-1); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            val lang = when (language) {
                "en" -> "en-US"
                "zh" -> "zh-CN"
                else -> java.util.Locale.getDefault().toString()
            }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _isListening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                _isListening.value = false
                _partialText.value = ""
                errorCallback?.invoke(error)
            }
            override fun onResults(results: Bundle?) {
                _isListening.value = false
                _partialText.value = ""
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.takeIf { it.isNotBlank() }
                if (text != null) resultCallback?.invoke(text) else errorCallback?.invoke(SpeechRecognizer.ERROR_NO_MATCH)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.firstOrNull()?.let { _partialText.value = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            engine.startListening(intent)
        } catch (_: Throwable) {
            _isListening.value = false
            errorCallback?.invoke(-1)
        }
    }

    fun stopListening() {
        _isListening.value = false
        _partialText.value = ""
        try { recognizer?.stopListening() } catch (_: Throwable) {}
    }

    fun shutdown() {
        _isListening.value = false
        _isAvailable.value = false
        _partialText.value = ""
        initializing = false
        try { recognizer?.stopListening() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        initialized = false
    }
}
