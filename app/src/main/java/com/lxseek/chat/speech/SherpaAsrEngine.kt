package com.lxseek.chat.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val RECORD_INTERVAL_MS = 100L

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
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var resultCallback: ((String) -> Unit)? = null
    @Volatile private var errorCallback: ((Int) -> Unit)? = null

    override fun init(context: Context): Boolean {
        if (nativeLoaded) return true
        nativeLoaded = try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Throwable) {
            false
        }
        _isAvailable.value = nativeLoaded
        return nativeLoaded
    }

    /**
     * Loads a streaming zipformer transducer model from [modelDir].
     * Expected files: encoder-epoch-99-avg-1.onnx, decoder-epoch-99-avg-1.onnx,
     * joiner-epoch-99-avg-1.onnx, tokens.txt.
     * Returns true on success.
     */
    fun loadModel(modelDir: String): Boolean {
        if (!nativeLoaded) return false
        val dir = File(modelDir)
        if (!dir.isDirectory) return false
        return try {
            recognizer?.release()
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "${dir.absolutePath}/encoder-epoch-99-avg-1.onnx",
                        decoder = "${dir.absolutePath}/decoder-epoch-99-avg-1.onnx",
                        joiner = "${dir.absolutePath}/joiner-epoch-99-avg-1.onnx",
                    ),
                    tokens = "${dir.absolutePath}/tokens.txt",
                    numThreads = 2,
                    modelType = "zipformer",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.4f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
            )
            recognizer = OnlineRecognizer(config = config)
            _isModelLoaded.value = true
            true
        } catch (_: Throwable) {
            _isModelLoaded.value = false
            false
        }
    }

    override fun startListening(
        context: Context,
        language: String,
        onResult: (String) -> Unit,
        onError: (Int) -> Unit,
    ) {
        if (!nativeLoaded) {
            onError(SpeechError.NOT_AVAILABLE)
            return
        }
        val rec = recognizer
        if (rec == null || !_isModelLoaded.value) {
            onError(SpeechError.MODEL_NOT_LOADED)
            return
        }
        resultCallback = onResult
        errorCallback = onError
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufSize = (minBuf * 2).coerceAtLeast(3200)
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize,
            )
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
            startRecognitionLoop(rec, ar, bufSize)
        } catch (_: Throwable) {
            onError(SpeechError.AUDIO_CAPTURE)
            cleanupAudio()
        }
    }

    private fun startRecognitionLoop(rec: OnlineRecognizer, ar: AudioRecord, bufSize: Int) {
        recordThread = Thread {
            val stream = rec.createStream()
            val buffer = ShortArray(bufSize)
            try {
                while (isRecording) {
                    val read = ar.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val samples = FloatArray(read) { buffer[it] / 32768.0f }
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (rec.isReady(stream)) {
                            rec.decode(stream)
                        }
                        val result = rec.getResult(stream)
                        if (result.text.isNotBlank()) {
                            _partialText.value = result.text
                        }
                        if (rec.isEndpoint(stream)) {
                            val text = result.text.trim()
                            rec.reset(stream)
                            _partialText.value = ""
                            if (text.isNotEmpty()) {
                                mainHandler.post { resultCallback?.invoke(text) }
                                break
                            }
                        }
                    } else if (read < 0) {
                        mainHandler.post { errorCallback?.invoke(SpeechError.AUDIO_CAPTURE) }
                        break
                    }
                    Thread.sleep(RECORD_INTERVAL_MS)
                }
            } catch (_: Throwable) {
                mainHandler.post { errorCallback?.invoke(SpeechError.GENERIC) }
            } finally {
                try { stream.release() } catch (_: Throwable) {}
            }
        }.also { it.isDaemon = true; it.name = "SherpaAsrEngine-Loop" }
        recordThread?.start()
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
        recordThread?.let { t ->
            try { t.join(500) } catch (_: Throwable) {}
        }
        recordThread = null
    }

    override fun shutdown() {
        stopListening()
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        _isAvailable.value = false
        _isModelLoaded.value = false
        nativeLoaded = false
    }
}
