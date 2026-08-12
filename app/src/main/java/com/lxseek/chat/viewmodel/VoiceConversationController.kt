package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.util.SttManager
import com.lxseek.chat.util.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TTS_START_GRACE_MS = 5_000L

/**
 * State machine for continuous voice conversation (STT → send → TTS → STT loop).
 *
 * States: IDLE → LISTENING → PROCESSING → SPEAKING → LISTENING → …
 *
 * The controller is self-contained: ChatViewModel delegates [toggle] / [stop] to it and
 * surfaces [state] / [partialTranscript] to the UI. All STT/TTS orchestration lives here
 * so ChatViewModel stays under the 999-line source-size cap.
 */
class VoiceConversationController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val languageProvider: () -> String,
    private val ttsAutoPlayOn: () -> Boolean,
    private val isLoading: StateFlow<Boolean>,
    private val ttsPlayingMessageId: StateFlow<String?>,
    private val sendMessage: suspend (String) -> Unit,
) {
    enum class State { IDLE, LISTENING, PROCESSING, SPEAKING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    val partialTranscript: StateFlow<String> = SttManager.partialText
    val isListening: StateFlow<Boolean> = SttManager.isListening

    @Volatile private var active = false
    private var observeJob: Job? = null
    private var ttsObserverJob: Job? = null
    private var sendJob: Job? = null
    @Volatile private var waitingForLlm = false
    @Volatile private var llmWasLoading = false

    fun toggle() {
        if (active) stop() else start()
    }

    fun start() {
        if (active) return
        active = true
        waitingForLlm = false
        llmWasLoading = false
        beginListening()
        observeJob?.cancel()
        observeJob = scope.launch { observeLlmAndTts() }
        if (ttsObserverJob == null) {
            ttsObserverJob = scope.launch { observeTtsPlaying() }
        }
    }

    fun stop() {
        active = false
        waitingForLlm = false
        llmWasLoading = false
        observeJob?.cancel()
        observeJob = null
        sendJob?.cancel()
        sendJob = null
        SttManager.stopListening()
        TtsManager.stop()
        _state.value = State.IDLE
    }

    private fun beginListening() {
        if (!active) return
        _state.value = State.LISTENING
        SttManager.init(appContext)
        SttManager.startListening(
            context = appContext,
            language = languageProvider(),
            onResult = { text ->
                if (!active) return@startListening
                SttManager.stopListening()
                _state.value = State.PROCESSING
                waitingForLlm = true
                llmWasLoading = false
                sendJob = scope.launch { sendMessage(text) }
            },
            onError = { _ ->
                if (!active) return@startListening
                SttManager.stopListening()
                _state.value = State.IDLE
                active = false
                observeJob?.cancel()
                observeJob = null
            },
        )
    }

    private suspend fun observeLlmAndTts() {
        isLoading.collectLatest { loading ->
            if (loading) {
                llmWasLoading = true
            } else if (llmWasLoading && waitingForLlm) {
                waitingForLlm = false
                llmWasLoading = false
                if (!active) return@collectLatest
                if (ttsAutoPlayOn()) {
                    _state.value = State.SPEAKING
                    withTimeoutOrNull(TTS_START_GRACE_MS) {
                        TtsManager.isPlaying.first { it }
                    }
                    if (!active) return@collectLatest
                    if (!TtsManager.isPlaying.value && _state.value == State.SPEAKING) {
                        beginListening()
                    }
                } else {
                    beginListening()
                }
            }
        }
    }

    private suspend fun observeTtsPlaying() {
        TtsManager.isPlaying.collect { playing ->
            if (!active) return@collect
            if (!playing && _state.value == State.SPEAKING) {
                delay(300)
                if (active && !TtsManager.isPlaying.value && _state.value == State.SPEAKING) {
                    beginListening()
                }
            }
        }
    }
}
