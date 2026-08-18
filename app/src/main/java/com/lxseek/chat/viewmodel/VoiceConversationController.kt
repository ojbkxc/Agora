package com.lxseek.chat.viewmodel

import android.content.Context
import android.speech.SpeechRecognizer
import com.lxseek.chat.util.AppLog as Log
import com.lxseek.chat.speech.AudioCaptureManager
import com.lxseek.chat.speech.SpeechRecognizerManager
import com.lxseek.chat.speech.VoskTranscriber
import com.lxseek.chat.speech.WhisperTranscriber
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
import java.io.File

private const val TTS_START_GRACE_MS = 5_000L
private const val TAG = "VoiceConvCtrl"

class VoiceConversationController(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val voiceLanguageProvider: () -> String,
    private val ttsAutoPlayOn: () -> Boolean,
    private val isLoading: StateFlow<Boolean>,
    private val sendMessage: suspend (String) -> Unit,
    private val asrEnginePref: () -> String,
    private val whisperApiKey: () -> String?,
    private val whisperBaseUrl: () -> String,
    private val whisperModel: () -> String,
) {
    enum class State { IDLE, LISTENING, TRANSCRIBING, PROCESSING, SPEAKING }
    enum class Mode { CONVERSATION, SINGLE_ASR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _mode = MutableStateFlow(Mode.CONVERSATION)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _singleAsrResult = MutableStateFlow<String?>(null)
    val singleAsrResult: StateFlow<String?> = _singleAsrResult.asStateFlow()

    private val audioCaptureManager = AudioCaptureManager(appContext)
    private val voskTranscriber = VoskTranscriber(appContext)
    private val speechRecognizerManager = SpeechRecognizerManager(appContext)
    private val whisperTranscriber = WhisperTranscriber(
        apiKeyProvider = { whisperApiKey() },
        baseUrlProvider = { whisperBaseUrl() },
        modelProvider = { whisperModel() },
    )

    @Volatile private var active = false
    @Volatile private var currentEngine: String = "auto"
    private var captureJob: Job? = null
    private var observeJob: Job? = null
    private var ttsObserverJob: Job? = null
    private var sendJob: Job? = null
    private var partialJob: Job? = null
    @Volatile private var waitingForLlm = false
    @Volatile private var llmWasLoading = false

    fun toggle() {
        Log.i(TAG, "toggle: state=${_state.value}, active=$active")
        when (_state.value) {
            State.IDLE, State.SPEAKING -> start()
            State.LISTENING -> stopCaptureAndTranscribe()
            else -> stop()
        }
    }

    fun start() {
        if (active) {
            Log.w(TAG, "start() called but active=true, state=${_state.value} — previous session not reset; forcing reset before restart")
            stop()
        }
        Log.i(TAG, "start: beginning voice conversation")
        _mode.value = Mode.CONVERSATION
        _singleAsrResult.value = null
        active = true
        waitingForLlm = false
        llmWasLoading = false
        try {
            beginListening()
        } catch (e: Throwable) {
            Log.e(TAG, "start() crashed: ${e.javaClass.simpleName}: ${e.message}", e)
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

    /**
     * Single-shot ASR: record once, transcribe, publish result via [singleAsrResult].
     * Does NOT send the message or observe LLM/TTS — the UI inserts the text into the composer.
     */
    fun startSingleAsr() {
        if (active) {
            Log.w(TAG, "startSingleAsr: active=true, stopping previous")
            stop()
        }
        Log.i(TAG, "startSingleAsr: beginning single ASR")
        _mode.value = Mode.SINGLE_ASR
        _singleAsrResult.value = null
        active = true
        waitingForLlm = false
        llmWasLoading = false
        try {
            beginListening()
        } catch (e: Throwable) {
            Log.e(TAG, "startSingleAsr crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            active = false
            _state.value = State.IDLE
            _mode.value = Mode.CONVERSATION
        }
    }

    fun clearSingleAsrResult() {
        _singleAsrResult.value = null
    }

    /**
     * Stop single ASR recording and transcribe. Result lands in [singleAsrResult].
     */
    fun stopSingleAsr() {
        if (_mode.value != Mode.SINGLE_ASR) {
            Log.w(TAG, "stopSingleAsr: not in SINGLE_ASR mode, ignoring")
            return
        }
        Log.i(TAG, "stopSingleAsr: engine=$currentEngine")
        stopCaptureAndTranscribe()
    }

    fun stop() {
        Log.i(TAG, "stop()")
        active = false
        waitingForLlm = false
        llmWasLoading = false
        observeJob?.cancel()
        observeJob = null
        sendJob?.cancel()
        sendJob = null
        partialJob?.cancel()
        partialJob = null
        captureJob?.cancel()
        captureJob = null
        try { audioCaptureManager.cancelCapture() } catch (e: Throwable) { Log.e(TAG, "cancelCapture failed", e) }
        try { speechRecognizerManager.stopListening() } catch (e: Throwable) { Log.e(TAG, "stopListening failed", e) }
        TtsManager.stop()
        _state.value = State.IDLE
        _partialTranscript.value = ""
        _amplitude.value = 0f
        _mode.value = Mode.CONVERSATION
        _singleAsrResult.value = null
    }

    /**
     * Release native/hardware resources owned by the speech engines.
     * Called from the ViewModel's onCleared so AudioRecord, SpeechRecognizer and
     * Vosk models do not leak across ViewModel destruction.
     */
    fun dispose() {
        Log.i(TAG, "dispose()")
        stop()
        try { audioCaptureManager.release() } catch (e: Throwable) { Log.e(TAG, "audioCaptureManager.release failed", e) }
        try { voskTranscriber.release() } catch (e: Throwable) { Log.e(TAG, "voskTranscriber.release failed", e) }
        try { speechRecognizerManager.destroy() } catch (e: Throwable) { Log.e(TAG, "speechRecognizerManager.destroy failed", e) }
    }

    private fun beginListening() {
        if (!active) return
        _state.value = State.LISTENING
        _partialTranscript.value = ""
        _amplitude.value = 0f

        val pref = try { asrEnginePref() } catch (e: Throwable) { Log.e(TAG, "asrEnginePref crashed: ${e.message}", e); "auto" }
        currentEngine = pref
        Log.i(TAG, "beginListening: pref=$pref")

        try {
            when (pref) {
                "vosk" -> beginVoskCapture()
                "whisper" -> beginWhisperCapture()
                "system" -> beginSystemListening()
                else -> beginAutoListening()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "beginListening dispatch crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            _state.value = State.IDLE
            active = false
        }
    }

    private fun beginAutoListening() {
        scope.launch {
            try {
                val lang = resolveVoskLanguage()
                val ready = voskTranscriber.initialize(lang)
                if (ready && active) {
                    Log.i(TAG, "auto: vosk ready, starting vosk capture")
                    beginVoskCapture()
                } else if (active) {
                    val hasKey = !whisperApiKey().isNullOrBlank()
                    if (hasKey) {
                        Log.i(TAG, "auto: vosk not ready, whisper key available, starting whisper capture")
                        beginWhisperCapture()
                    } else {
                        Log.i(TAG, "auto: vosk not ready, no whisper key, falling back to system")
                        beginSystemListening()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "beginAutoListening launch crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                if (active) {
                    _state.value = State.IDLE
                    active = false
                }
            }
        }
    }

    /** Resolve the configured voice recognition language to a Vosk model code. Prefers an exact
     *  downloaded model, then a downloaded model with the same base code, then any installed
     *  model — so offline recognition still engages instead of being silently skipped. */
    private fun resolveVoskLanguage(): String {
        val pref = try { voiceLanguageProvider().trim().lowercase() } catch (e: Throwable) {
            Log.e(TAG, "voiceLanguageProvider crashed: ${e.message}", e); "en"
        }
        val downloaded = try { voskTranscriber.getDownloadedLanguages() } catch (e: Throwable) { emptyList() }

        // No offline model installed at all: return the requested code so initialize() fails
        // with a clear reason and the caller falls back to whisper/system.
        if (downloaded.isEmpty()) {
            return if (pref.isBlank() || pref == "system") "en" else pref.split("-").first()
        }

        // User didn't pin a language: engage offline with whatever model is installed instead
        // of defaulting to "en" and silently skipping an installed non-English model.
        if (pref.isBlank() || pref == "system") {
            Log.i(TAG, "No pinned Vosk language, using installed model: ${downloaded.first()}")
            return downloaded.first()
        }

        val base = pref.split("-").first()
        // Prefer an exact downloaded model; otherwise pick a downloaded model whose
        // base code matches (e.g. "zh" selected while "zh-full" is downloaded).
        if (downloaded.contains(base)) return base
        downloaded.firstOrNull { VoskTranscriber.getBaseLanguageCode(it) == base }?.let { return it }

        // No matching model downloaded: fall back to any installed model so offline
        // recognition still engages.
        Log.w(TAG, "No downloaded Vosk model for '$pref', falling back to ${downloaded.first()}")
        return downloaded.first()
    }

    private fun beginVoskCapture() {
        Log.i(TAG, "beginVoskCapture: starting audio capture for vosk")
        currentEngine = "vosk"
        startAudioCapture { wavFile ->
            scope.launch { transcribeWithVosk(wavFile) }
        }
    }

    private fun beginWhisperCapture() {
        Log.i(TAG, "beginWhisperCapture: starting audio capture for whisper")
        currentEngine = "whisper"
        startAudioCapture { wavFile ->
            scope.launch { transcribeWithWhisper(wavFile) }
        }
    }

    private fun startAudioCapture(onComplete: (File) -> Unit) {
        captureJob?.cancel()
        captureJob = scope.launch {
            try {
                val captureFlow = audioCaptureManager.startCapture()
                captureFlow.collect { chunk ->
                    if (!active) return@collect
                    var max = 0
                    for (b in chunk) {
                        val v = if (b < 0) -b.toInt() else b.toInt()
                        if (v > max) max = v
                    }
                    _amplitude.value = (max / 128f).coerceIn(0f, 1f)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Audio capture flow crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                if (active) {
                    _state.value = State.IDLE
                    active = false
                }
            }
        }
    }

    private fun stopCaptureAndTranscribe() {
        Log.i(TAG, "stopCaptureAndTranscribe: engine=$currentEngine")
        if (currentEngine == "system") {
            try { speechRecognizerManager.stopListening() } catch (e: Throwable) { Log.e(TAG, "stopListening failed", e) }
            partialJob?.cancel()
            partialJob = null
            val partial = _partialTranscript.value
            _partialTranscript.value = ""
            _amplitude.value = 0f
            if (partial.isNotBlank()) {
                handleTranscriptionResult(partial)
            } else {
                _state.value = State.IDLE
                active = false
            }
            return
        }
        if (currentEngine == "vosk" || currentEngine == "whisper" || currentEngine == "auto") {
            _state.value = State.TRANSCRIBING
            captureJob?.cancel()
            captureJob = null
            scope.launch {
                try {
                    val wavFile = audioCaptureManager.stopCapture()
                    Log.i(TAG, "WAV file: ${wavFile.absolutePath} (${wavFile.length()} bytes)")
                    _amplitude.value = 0f
                    when (currentEngine) {
                        "whisper" -> transcribeWithWhisper(wavFile)
                        "auto" -> {
                            if (voskTranscriber.isReady()) transcribeWithVosk(wavFile)
                            else if (!whisperApiKey().isNullOrBlank()) transcribeWithWhisper(wavFile)
                            else {
                                Log.w(TAG, "auto: no engine ready for transcription")
                                wavFile.delete()
                                if (active) beginSystemListening()
                            }
                        }
                        else -> transcribeWithVosk(wavFile)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "stopCaptureAndTranscribe crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                    if (active) {
                        _state.value = State.IDLE
                    }
                }
            }
        }
    }

    private suspend fun transcribeWithVosk(wavFile: File) {
        _state.value = State.TRANSCRIBING
        try {
            val langCode = resolveVoskLanguage()
            if (!voskTranscriber.isReady()) {
                val initialized = voskTranscriber.initialize(langCode)
                if (!initialized) {
                    Log.w(TAG, "Vosk model not available for $langCode, trying en")
                    voskTranscriber.initialize("en")
                }
            }
            Log.i(TAG, "Vosk transcribing ${wavFile.name}...")
            val text = voskTranscriber.transcribe(wavFile)
            wavFile.delete()
            handleTranscriptionResult(text)
        } catch (e: Throwable) {
            Log.e(TAG, "transcribeWithVosk crashed: ${e.message}", e)
            wavFile.delete()
            if (active) {
                _state.value = State.IDLE
            }
        }
    }

    private suspend fun transcribeWithWhisper(wavFile: File) {
        _state.value = State.TRANSCRIBING
        try {
            val langCode = resolveVoskLanguage()
            val languageParam = when (langCode) { "en" -> "en"; "zh" -> "zh"; else -> null }
            Log.i(TAG, "Whisper transcribing ${wavFile.name}...")
            val result = whisperTranscriber.transcribe(wavFile, languageParam)
            if (result.isSuccess) {
                wavFile.delete()
                handleTranscriptionResult(result.getOrDefault(""))
            } else {
                Log.w(TAG, "Whisper failed: ${result.exceptionOrNull()?.message}")
                if (active && voskTranscriber.isReady()) {
                    Log.i(TAG, "Falling back to vosk with the same recording")
                    transcribeWithVosk(wavFile)
                } else {
                    wavFile.delete()
                    if (active) {
                        _state.value = State.IDLE
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "transcribeWithWhisper crashed: ${e.message}", e)
            wavFile.delete()
            if (active) {
                _state.value = State.IDLE
            }
        }
    }

    private fun beginSystemListening() {
        // GrapheneOS AI checks availability before dispatching to the system engine;
        // when unavailable, fall back to Vosk/Whisper instead of failing silently.
        val available = try { speechRecognizerManager.isAvailable() } catch (e: Throwable) {
            Log.e(TAG, "isAvailable check crashed: ${e.message}", e); false
        }
        if (!available) {
            Log.w(TAG, "System ASR unavailable, trying fallback engines")
            val voskReady = try { voskTranscriber.isReady() } catch (e: Throwable) { false }
            if (voskReady) {
                Log.i(TAG, "system unavailable, falling back to vosk")
                beginVoskCapture()
            } else if (!whisperApiKey().isNullOrBlank()) {
                Log.i(TAG, "system unavailable, falling back to whisper")
                beginWhisperCapture()
            } else {
                Log.e(TAG, "system unavailable and no fallback engine ready")
                if (active) {
                    _state.value = State.IDLE
                    active = false
                }
            }
            return
        }
        Log.i(TAG, "beginSystemListening: starting system ASR")
        currentEngine = "system"
        try {
            partialJob?.cancel()
            partialJob = scope.launch {
                speechRecognizerManager.startListening().collect { result ->
                    if (!active) return@collect
                    when (result) {
                        is SpeechRecognizerManager.RecognitionResult.Partial -> {
                            _partialTranscript.value = result.text
                        }
                        is SpeechRecognizerManager.RecognitionResult.Final -> {
                            Log.i(TAG, "System ASR final: '${result.text}'")
                            speechRecognizerManager.stopListening()
                            handleTranscriptionResult(result.text)
                        }
                        is SpeechRecognizerManager.RecognitionResult.Error -> {
                            Log.w(TAG, "System ASR error: ${result.message} (code=${result.code})")
                            // GrapheneOS AI: on ERROR_CLIENT(5) the system recognizer is
                            // broken; switch to Vosk so the conversation survives.
                            if (active) {
                                val shouldSwitchToVosk = result.code == SpeechRecognizer.ERROR_CLIENT &&
                                    voskTranscriber.isReady()
                                if (shouldSwitchToVosk) {
                                    Log.i(TAG, "Switching to vosk after system ERROR_CLIENT")
                                    speechRecognizerManager.stopListening()
                                    beginVoskCapture()
                                } else {
                                    _state.value = State.IDLE
                                    active = false
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "beginSystemListening crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            _state.value = State.IDLE
            active = false
        }
    }

    private fun handleTranscriptionResult(text: String) {
        if (!active) return
        val cleanText = text.trim()
        if (cleanText.isBlank() || cleanText.startsWith("[")) {
            Log.w(TAG, "Transcription empty or error: '$cleanText'")
            if (active) {
                _state.value = State.IDLE
                active = false
                _mode.value = Mode.CONVERSATION
            }
            return
        }
        Log.i(TAG, "Transcription result: '$cleanText' (mode=${_mode.value})")
        when (_mode.value) {
            Mode.SINGLE_ASR -> {
                _singleAsrResult.value = cleanText
                _state.value = State.IDLE
                active = false
                _partialTranscript.value = ""
                _amplitude.value = 0f
            }
            Mode.CONVERSATION -> {
                _state.value = State.PROCESSING
                waitingForLlm = true
                llmWasLoading = false
                sendJob = scope.launch { sendMessage(cleanText) }
            }
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

    fun getVoskTranscriber(): VoskTranscriber = voskTranscriber
}
