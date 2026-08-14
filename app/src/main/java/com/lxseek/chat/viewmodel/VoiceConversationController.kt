package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.speech.RemoteTranscriber
import com.lxseek.chat.util.SttManager
import com.lxseek.chat.util.TtsManager
import com.lxseek.chat.util.VoiceRecorder
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
import java.io.File

private const val TTS_START_GRACE_MS = 5_000L
private const val STT_ERROR_RETRY_MAX = 3

class VoiceConversationController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val languageProvider: () -> String,
    private val ttsAutoPlayOn: () -> Boolean,
    private val isLoading: StateFlow<Boolean>,
    private val sendMessage: suspend (String) -> Unit,
    private val useRemoteAsr: () -> Boolean,
    private val remoteAsrBaseUrl: () -> String,
    private val remoteAsrApiKey: () -> String,
    private val remoteAsrModel: () -> String,
) {
    enum class State { IDLE, LISTENING, TRANSCRIBING, PROCESSING, SPEAKING }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val recorder = VoiceRecorder()
    val amplitude: StateFlow<Float> = recorder.amplitude

    @Volatile private var active = false
    private var observeJob: Job? = null
    private var ttsObserverJob: Job? = null
    private var sendJob: Job? = null
    private var partialJob: Job? = null
    @Volatile private var waitingForLlm = false
    @Volatile private var llmWasLoading = false
    @Volatile private var sttErrorCount = 0

    fun toggle() {
        if (active) stop() else start()
    }

    fun start() {
        if (active) return
        active = true
        sttErrorCount = 0
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
        partialJob?.cancel()
        partialJob = null
        recorder.stop()
        SttManager.stopListening()
        TtsManager.stop()
        _state.value = State.IDLE
        _partialTranscript.value = ""
    }

    private fun beginListening() {
        if (!active) return
        _state.value = State.LISTENING
        _partialTranscript.value = ""
        if (useRemoteAsr() && remoteAsrApiKey().isNotBlank()) {
            beginRemoteListening()
        } else {
            beginSystemListening()
        }
    }

    private fun beginRemoteListening() {
        recorder.start(
            context = appContext,
            onComplete = { file ->
                if (!active) return@start
                _state.value = State.TRANSCRIBING
                scope.launch {
                    val lang = languageProvider()
                    val languageParam = when (lang) {
                        "en" -> "en"
                        "zh" -> "zh"
                        else -> null
                    }
                    val text = RemoteTranscriber.transcribe(
                        baseUrl = remoteAsrBaseUrl(),
                        apiKey = remoteAsrApiKey(),
                        audioFile = file,
                        model = remoteAsrModel(),
                        language = languageParam,
                    )
                    file.delete()
                    if (!active) return@launch
                    if (text != null) {
                        sttErrorCount = 0
                        _state.value = State.PROCESSING
                        waitingForLlm = true
                        llmWasLoading = false
                        sendJob = scope.launch { sendMessage(text) }
                    } else {
                        sttErrorCount++
                        if (sttErrorCount >= STT_ERROR_RETRY_MAX) {
                            _state.value = State.IDLE
                            active = false
                        } else if (active) {
                            delay(500)
                            beginListening()
                        }
                    }
                }
            },
            onError = { _ ->
                if (!active) return@start
                sttErrorCount++
                if (sttErrorCount >= STT_ERROR_RETRY_MAX) {
                    _state.value = State.IDLE
                    active = false
                    observeJob?.cancel()
                    observeJob = null
                } else if (active) {
                    beginSystemListening()
                }
            },
        )
    }

    private fun beginSystemListening() {
        partialJob?.cancel()
        partialJob = scope.launch {
            SttManager.partialText.collect { _partialTranscript.value = it }
        }
        SttManager.init(appContext)
        SttManager.startListening(
            context = appContext,
            language = languageProvider(),
            onResult = { text ->
                if (!active) return@startListening
                SttManager.stopListening()
                sttErrorCount = 0
                _state.value = State.PROCESSING
                waitingForLlm = true
                llmWasLoading = false
                sendJob = scope.launch { sendMessage(text) }
            },
            onError = { _ ->
                if (!active) return@startListening
                SttManager.stopListening()
                sttErrorCount++
                if (sttErrorCount >= STT_ERROR_RETRY_MAX) {
                    _state.value = State.IDLE
                    active = false
                    observeJob?.cancel()
                    observeJob = null
                } else if (active) {
                    scope.launch {
                        delay(500)
                        if (active) beginListening()
                    }
                }
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
