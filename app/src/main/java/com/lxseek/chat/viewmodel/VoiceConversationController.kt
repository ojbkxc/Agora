package com.lxseek.chat.viewmodel

import android.content.Context
import com.lxseek.chat.util.AppLog as Log
import com.lxseek.chat.speech.RemoteTranscriber
import com.lxseek.chat.speech.SpeechRecognitionManager
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
private const val TAG = "VoiceConvCtrl"

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
    private val asrEnginePref: () -> String,
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
        Log.i(TAG, "toggle: active=$active → ${!active}")
        if (active) stop() else start()
    }

    fun start() {
        if (active) return
        active = true
        sttErrorCount = 0
        waitingForLlm = false
        llmWasLoading = false
        try {
            beginListening()
        } catch (e: Throwable) {
            Log.e(TAG, "start() beginListening crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            active = false
            _state.value = State.IDLE
            return
        }
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
        SpeechRecognitionManager.sherpaEngine.stopListening()
        SpeechRecognitionManager.voskEngine.stopListening()
        TtsManager.stop()
        _state.value = State.IDLE
        _partialTranscript.value = ""
    }

    private fun beginListening() {
        if (!active) return
        _state.value = State.LISTENING
        _partialTranscript.value = ""
        val sherpaReady = try {
            SpeechRecognitionManager.sherpaEngine.let {
                it.init(appContext)
                it.isAvailable.value && it.isModelLoaded.value
            }
        } catch (e: Throwable) {
            Log.e(TAG, "beginListening: sherpa init crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
        val voskReady = try {
            SpeechRecognitionManager.voskEngine.let {
                it.init(appContext)
                it.isAvailable.value && it.isModelLoaded.value
            }
        } catch (e: Throwable) {
            Log.e(TAG, "beginListening: vosk init crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
        val pref = try { asrEnginePref() } catch (e: Throwable) { Log.e(TAG, "asrEnginePref crashed: ${e.message}", e); "auto" }
        val useRemote = try { useRemoteAsr() } catch (e: Throwable) { Log.e(TAG, "useRemoteAsr crashed: ${e.message}", e); false }
        val remoteKeyBlank = try { remoteAsrApiKey().isNotBlank() } catch (e: Throwable) { Log.e(TAG, "remoteAsrApiKey crashed: ${e.message}", e); false }
        Log.i(TAG, "beginListening: pref=$pref, sherpaReady=$sherpaReady, voskReady=$voskReady, useRemote=$useRemote, remoteKeyBlank=$remoteKeyBlank")
        try {
            when {
                useRemote && remoteKeyBlank -> beginRemoteListening()
                pref == "vosk" && voskReady -> beginVoskListening()
                (pref == "sherpa-onnx" || pref == "auto") && sherpaReady -> beginSherpaListening()
                pref == "auto" && voskReady -> beginVoskListening()
                else -> {
                    if ((pref == "sherpa-onnx" || pref == "auto") && !sherpaReady) {
                        Log.w(TAG, "Falling back: sherpa pref=$pref but sherpaReady=false (native=${SpeechRecognitionManager.sherpaEngine.isAvailable.value}, model=${SpeechRecognitionManager.sherpaEngine.isModelLoaded.value}, error=${SpeechRecognitionManager.sherpaEngine.lastError.value})")
                    }
                    if (pref == "vosk" && !voskReady) {
                        Log.w(TAG, "Falling back: vosk pref=$pref but voskReady=false (available=${SpeechRecognitionManager.voskEngine.isAvailable.value}, model=${SpeechRecognitionManager.voskEngine.isModelLoaded.value}, error=${SpeechRecognitionManager.voskEngine.lastError.value})")
                    }
                    beginSystemListening()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "beginListening: dispatch crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            _state.value = State.IDLE
            active = false
        }
    }

    private fun beginRemoteListening() {
        Log.i(TAG, "beginRemoteListening: starting recorder")
        try {
            recorder.start(
                context = appContext,
                onComplete = { file ->
                    if (!active) return@start
                    Log.i(TAG, "Recorder onComplete: ${file.absolutePath} (${file.length()} bytes)")
                    _state.value = State.TRANSCRIBING
                    scope.launch {
                        try {
                            val lang = languageProvider()
                            val languageParam = when (lang) {
                                "en" -> "en"
                                "zh" -> "zh"
                                else -> null
                            }
                            val baseUrl = remoteAsrBaseUrl()
                            val apiKey = remoteAsrApiKey()
                            val model = remoteAsrModel()
                            Log.i(TAG, "RemoteTranscriber: baseUrl=$baseUrl, model=$model, lang=$languageParam")
                            val text = RemoteTranscriber.transcribe(
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                audioFile = file,
                                model = model,
                                language = languageParam,
                            )
                            file.delete()
                            if (!active) return@launch
                            if (text != null) {
                                Log.i(TAG, "RemoteTranscriber result: '$text'")
                                sttErrorCount = 0
                                _state.value = State.PROCESSING
                                waitingForLlm = true
                                llmWasLoading = false
                                sendJob = scope.launch { sendMessage(text) }
                            } else {
                                Log.w(TAG, "RemoteTranscriber returned null (error or empty)")
                                sttErrorCount++
                                if (sttErrorCount >= STT_ERROR_RETRY_MAX) {
                                    _state.value = State.IDLE
                                    active = false
                                } else if (active) {
                                    delay(500)
                                    beginListening()
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "beginRemoteListening coroutine crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                            file.delete()
                            if (active) {
                                sttErrorCount++
                                if (sttErrorCount >= STT_ERROR_RETRY_MAX) {
                                    _state.value = State.IDLE
                                    active = false
                                } else {
                                    _state.value = State.IDLE
                                }
                            }
                        }
                    }
                },
                onError = { err ->
                    if (!active) return@start
                    Log.e(TAG, "Recorder onError: $err")
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
        } catch (e: Throwable) {
            Log.e(TAG, "beginRemoteListening: recorder.start crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            _state.value = State.IDLE
            active = false
        }
    }

    private fun beginSystemListening() {
        Log.i(TAG, "beginSystemListening: starting system ASR")
        try {
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
                    Log.i(TAG, "System ASR result: '$text'")
                    SttManager.stopListening()
                    sttErrorCount = 0
                    _state.value = State.PROCESSING
                    waitingForLlm = true
                    llmWasLoading = false
                    sendJob = scope.launch { sendMessage(text) }
                },
                onError = { err ->
                    if (!active) return@startListening
                    Log.w(TAG, "System ASR error: $err")
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
        } catch (e: Throwable) {
            Log.e(TAG, "beginSystemListening crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            _state.value = State.IDLE
            active = false
        }
    }

    private fun beginSherpaListening() {
        val engine = SpeechRecognitionManager.sherpaEngine
        if (!engine.isAvailable.value || !engine.isModelLoaded.value) {
            Log.w(TAG, "beginSherpaListening: engine not ready (available=${engine.isAvailable.value}, model=${engine.isModelLoaded.value}), falling back to system")
            beginSystemListening()
            return
        }
        Log.i(TAG, "beginSherpaListening: starting sherpa ASR (offline=${engine.lastError.value == null})")
        try {
            partialJob?.cancel()
            partialJob = scope.launch {
                engine.partialText.collect { _partialTranscript.value = it }
            }
            engine.startListening(
                context = appContext,
                language = languageProvider(),
                onResult = { text ->
                    if (!active) return@startListening
                    Log.i(TAG, "Sherpa ASR result: '$text'")
                    engine.stopListening()
                    sttErrorCount = 0
                    _state.value = State.PROCESSING
                    waitingForLlm = true
                    llmWasLoading = false
                    sendJob = scope.launch { sendMessage(text) }
                },
                onError = { err ->
                    if (!active) return@startListening
                    Log.w(TAG, "Sherpa ASR error: $err")
                    engine.stopListening()
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
        } catch (e: Throwable) {
            Log.e(TAG, "beginSherpaListening crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            beginSystemListening()
        }
    }

    private fun beginVoskListening() {
        val engine = SpeechRecognitionManager.voskEngine
        if (!engine.isAvailable.value || !engine.isModelLoaded.value) {
            Log.w(TAG, "beginVoskListening: engine not ready (available=${engine.isAvailable.value}, model=${engine.isModelLoaded.value}), falling back to system")
            beginSystemListening()
            return
        }
        Log.i(TAG, "beginVoskListening: starting vosk ASR (offline)")
        try {
            partialJob?.cancel()
            partialJob = scope.launch {
                engine.partialText.collect { _partialTranscript.value = it }
            }
            engine.startListening(
                context = appContext,
                language = languageProvider(),
                onResult = { text ->
                    if (!active) return@startListening
                    Log.i(TAG, "Vosk ASR result: '$text'")
                    engine.stopListening()
                    sttErrorCount = 0
                    _state.value = State.PROCESSING
                    waitingForLlm = true
                    llmWasLoading = false
                    sendJob = scope.launch { sendMessage(text) }
                },
                onError = { err ->
                    if (!active) return@startListening
                    Log.w(TAG, "Vosk ASR error: $err")
                    engine.stopListening()
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
        } catch (e: Throwable) {
            Log.e(TAG, "beginVoskListening crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            beginSystemListening()
        }
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
