package com.lxseek.chat.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

class SherpaAsrEngine : SpeechEngine {
    override val id: String = "sherpa-onnx"
    override val displayName: String = "Sherpa-ONNX (Offline)"

    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    override val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    override val requiresModel: Boolean = true

    private val _isModelLoaded = MutableStateFlow(false)
    override val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    @Volatile private var nativeLoaded = false
    @Volatile private var onlineRecognizer: OnlineRecognizer? = null
    @Volatile private var offlineRecognizer: OfflineRecognizer? = null
    @Volatile private var vad: Vad? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var useOfflineMode = false
    private var recordThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var resultCallback: ((String) -> Unit)? = null
    @Volatile private var errorCallback: ((Int) -> Unit)? = null

    private fun bestProvider(): String = try {
        System.loadLibrary("sherpa-onnx-jni")
        "cpu"
    } catch (_: Throwable) { "cpu" }

    private fun dynamicThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    override fun init(context: Context): Boolean {
        if (nativeLoaded && _isModelLoaded.value) return true
        if (!nativeLoaded) {
            nativeLoaded = try {
                System.loadLibrary("sherpa-onnx-jni")
                true
            } catch (_: UnsatisfiedLinkError) { false } catch (_: Throwable) { false }
            _isAvailable.value = nativeLoaded
        }
        if (nativeLoaded && !_isModelLoaded.value) {
            val zipformer = SherpaModelManager.ModelKind.ASR_ZIPFORMER_BILINGUAL
            val senseVoice = SherpaModelManager.ModelKind.ASR_SENSE_VOICE
            if (SherpaModelManager.isModelPresent(context, zipformer)) {
                loadModel(SherpaModelManager.modelDir(context, zipformer).absolutePath)
            } else if (SherpaModelManager.isModelPresent(context, senseVoice)) {
                loadSenseVoiceModel(SherpaModelManager.modelDir(context, senseVoice).absolutePath)
            }
        }
        return nativeLoaded
    }

    fun loadModel(modelDir: String): Boolean {
        if (!nativeLoaded) return false
        val dir = File(modelDir)
        if (!dir.isDirectory) return false
        val encoder = listOf("encoder-epoch-99-avg-1.int8.onnx", "encoder-epoch-99-avg-1.onnx").firstOrNull { File(dir, it).exists() } ?: return false
        val decoder = listOf("decoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.int8.onnx").firstOrNull { File(dir, it).exists() } ?: return false
        val joiner = listOf("joiner-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.onnx").firstOrNull { File(dir, it).exists() } ?: return false
        return try {
            onlineRecognizer?.release()
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "${dir.absolutePath}/$encoder",
                        decoder = "${dir.absolutePath}/$decoder",
                        joiner = "${dir.absolutePath}/$joiner",
                    ),
                    tokens = "${dir.absolutePath}/tokens.txt",
                    numThreads = dynamicThreads(),
                    provider = bestProvider(),
                    modelType = "zipformer",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.4f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
            )
            onlineRecognizer = OnlineRecognizer(config = config)
            useOfflineMode = false
            _isModelLoaded.value = true
            true
        } catch (_: Throwable) {
            _isModelLoaded.value = false
            false
        }
    }

    fun loadSenseVoiceModel(modelDir: String): Boolean {
        if (!nativeLoaded) return false
        val dir = File(modelDir)
        if (!dir.isDirectory) return false
        val modelFile = listOf("model.int8.onnx", "model.onnx").firstOrNull { File(dir, it).exists() } ?: return false
        return try {
            offlineRecognizer?.release()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "${dir.absolutePath}/$modelFile",
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = "${dir.absolutePath}/tokens.txt",
                    numThreads = dynamicThreads(),
                    provider = bestProvider(),
                ),
            )
            offlineRecognizer = OfflineRecognizer(config = config)
            useOfflineMode = true
            _isModelLoaded.value = true
            true
        } catch (_: Throwable) {
            _isModelLoaded.value = false
            false
        }
    }

    private fun initVadForOffline(context: Context): Boolean {
        val vadDir = SherpaModelManager.modelDir(context, SherpaModelManager.ModelKind.VAD)
        val vadFile = File(vadDir, "silero_vad.onnx")
        if (!vadFile.exists()) return false
        return try {
            vad?.release()
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = com.lxseek.chat.util.VoiceRecorder.vadThreshold,
                    minSilenceDuration = com.lxseek.chat.util.VoiceRecorder.vadMinSilence,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = com.lxseek.chat.util.VoiceRecorder.vadMaxSpeech,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
            vad = Vad(config = config)
            true
        } catch (_: Throwable) {
            vad = null
            false
        }
    }

    override fun startListening(
        context: Context,
        language: String,
        onResult: (String) -> Unit,
        onError: (Int) -> Unit,
    ) {
        if (!nativeLoaded) { onError(SpeechError.NOT_AVAILABLE); return }
        if (!_isModelLoaded.value) { onError(SpeechError.MODEL_NOT_LOADED); return }
        resultCallback = onResult
        errorCallback = onError
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufSize = (minBuf * 2).coerceAtLeast(3200)
            val ar = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                onError(SpeechError.AUDIO_CAPTURE)
                return
            }
            audioRecord = ar
            isRecording = true
            _isListening.value = true
            _partialText.value = ""
            ar.startRecording()
            if (useOfflineMode) {
                if (!initVadForOffline(context)) { onError(SpeechError.MODEL_NOT_LOADED); cleanupAudio(); return }
                startOfflineLoop(ar, bufSize)
            } else {
                val rec = onlineRecognizer ?: run { onError(SpeechError.MODEL_NOT_LOADED); cleanupAudio(); return }
                startOnlineLoop(rec, ar, bufSize)
            }
        } catch (_: Throwable) {
            onError(SpeechError.AUDIO_CAPTURE)
            cleanupAudio()
        }
    }

    private fun startOnlineLoop(rec: OnlineRecognizer, ar: AudioRecord, bufSize: Int) {
        recordThread = Thread {
            val stream = rec.createStream()
            val buffer = ShortArray(bufSize)
            try {
                while (isRecording) {
                    val read = ar.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val samples = FloatArray(read) { buffer[it] / 32768.0f }
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (rec.isReady(stream)) rec.decode(stream)
                        val result = rec.getResult(stream)
                        if (result.text.isNotBlank()) _partialText.value = result.text
                        if (rec.isEndpoint(stream)) {
                            val text = result.text.trim()
                            rec.reset(stream)
                            _partialText.value = ""
                            if (text.isNotEmpty()) {
                                isRecording = false
                                _isListening.value = false
                                mainHandler.post { resultCallback?.invoke(text) }
                                break
                            }
                        }
                    } else if (read < 0) { mainHandler.post { errorCallback?.invoke(SpeechError.AUDIO_CAPTURE) }; break }
                }
            } catch (_: Throwable) {
                mainHandler.post { errorCallback?.invoke(SpeechError.GENERIC) }
            } finally {
                try { stream.release() } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.name = "SherpaAsr-Online" }
        recordThread?.start()
    }

    private fun startOfflineLoop(ar: AudioRecord, bufSize: Int) {
        val rec = offlineRecognizer ?: run { mainHandler.post { errorCallback?.invoke(SpeechError.MODEL_NOT_LOADED) }; return }
        val vadInstance = vad ?: run { mainHandler.post { errorCallback?.invoke(SpeechError.MODEL_NOT_LOADED) }; return }
        recordThread = Thread {
            val windowSize = 512
            val buffer = ShortArray(bufSize)
            val allSamples = ArrayList<Float>()
            val startTime = SystemClock.elapsedRealtime()
            try {
                while (isRecording) {
                    val read = ar.read(buffer, 0, windowSize)
                    if (read > 0) {
                        val samples = FloatArray(read) { buffer[it] / 32768.0f }
                        vadInstance.acceptWaveform(samples)
                        while (!vadInstance.empty()) {
                            val segment = vadInstance.front()
                            allSamples.addAll(segment.samples.toList())
                            vadInstance.pop()
                        }
                        if (allSamples.isNotEmpty() && !vadInstance.isSpeechDetected()) {
                            val text = recognizeOffline(rec, allSamples.toFloatArray())
                            allSamples.clear()
                            if (text.isNotEmpty()) {
                                isRecording = false
                                _isListening.value = false
                                mainHandler.post { resultCallback?.invoke(text) }
                                break
                            }
                        }
                        if (SystemClock.elapsedRealtime() - startTime > 30_000L) {
                            if (allSamples.isNotEmpty()) {
                                val text = recognizeOffline(rec, allSamples.toFloatArray())
                                allSamples.clear()
                                if (text.isNotEmpty()) {
                                    isRecording = false
                                    _isListening.value = false
                                    mainHandler.post { resultCallback?.invoke(text) }
                                    break
                                }
                            }
                        }
                    } else if (read < 0) { mainHandler.post { errorCallback?.invoke(SpeechError.AUDIO_CAPTURE) }; break }
                }
            } catch (_: Throwable) {
                mainHandler.post { errorCallback?.invoke(SpeechError.GENERIC) }
            }
        }.also { it.isDaemon = true; it.name = "SherpaAsr-Offline" }
        recordThread?.start()
    }

    private fun recognizeOffline(rec: OfflineRecognizer, samples: FloatArray): String {
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            stream.release()
            result.text.trim()
        } catch (_: Throwable) { "" }
    }

    override fun stopListening() {
        isRecording = false
        _isListening.value = false
        _partialText.value = ""
        cleanupAudio()
    }

    private fun cleanupAudio() {
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        recordThread?.let { t -> try { t.join(500) } catch (_: Throwable) {} }
        recordThread = null
    }

    override fun shutdown() {
        stopListening()
        try { onlineRecognizer?.release() } catch (_: Throwable) {}
        try { offlineRecognizer?.release() } catch (_: Throwable) {}
        try { vad?.release() } catch (_: Throwable) {}
        onlineRecognizer = null
        offlineRecognizer = null
        vad = null
        _isAvailable.value = false
        _isModelLoaded.value = false
        nativeLoaded = false
    }
}
