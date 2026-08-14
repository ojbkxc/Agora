package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.speech.SpeechRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages one-shot voice input: user taps mic → listen → insert recognized text into composer.
 *
 * Unlike [VoiceConversationController] which runs a continuous STT→LLM→TTS loop,
 * this controller does a single recognition cycle and delivers the result via [onResult].
 * It uses [SpeechRecognitionManager] for engine selection (Auto/System/Sherpa).
 */
class VoiceInputController(
    private val appContext: Context,
    private val languageProvider: () -> String,
) {
    enum class State { IDLE, LISTENING, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = SttManager.partialText

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @Volatile private var active = false
    var onResult: ((String) -> Unit)? = null

    fun start(onResult: (String) -> Unit) {
        if (active) return
        this.onResult = onResult
        active = true
        _state.value = State.LISTENING
        _partialText.value = ""
        _errorMessage.value = null
        SpeechRecognitionManager.preferredEngine.let { /* trigger init */ }
        SpeechRecognitionManager.init(appContext)
        SpeechRecognitionManager.startListening(
            context = appContext,
            language = languageProvider(),
            onResult = { text ->
                if (!active) return@startListening
                active = false
                _state.value = State.IDLE
                _partialText.value = ""
                this.onResult?.invoke(text)
            },
            onError = { errorCode ->
                if (!active) return@startListening
                active = false
                _state.value = State.ERROR
                _partialText.value = ""
                _errorMessage.value = describeError(errorCode)
            },
        )
    }

    fun cancel() {
        active = false
        SpeechRecognitionManager.stopListening()
        _state.value = State.IDLE
        _partialText.value = ""
        _errorMessage.value = null
    }

    fun dismissError() {
        if (_state.value == State.ERROR) {
            _state.value = State.IDLE
            _errorMessage.value = null
        }
    }

    private fun describeError(code: Int): String = when (code) {
        com.lxseek.chat.speech.SpeechError.NO_MATCH -> "No speech detected"
        com.lxseek.chat.speech.SpeechError.NETWORK -> "Network error"
        com.lxseek.chat.speech.SpeechError.NOT_AVAILABLE -> "ASR engine not available"
        com.lxseek.chat.speech.SpeechError.MODEL_NOT_LOADED -> "ASR model not loaded"
        com.lxseek.chat.speech.SpeechError.AUDIO_CAPTURE -> "Audio capture failed"
        else -> "Recognition error ($code)"
    }
}
