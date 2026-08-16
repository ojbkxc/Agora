package com.lxseek.chat.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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

private const val TAG = "SherpaAsrEngine"
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

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

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

    private fun bestProvider(): String = "cpu"

    private fun dynamicThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    override fun init(context: Context): Boolean {
        Log.d(TAG, "init() called, nativeLoaded=$nativeLoaded, modelLoaded=${_isModelLoaded.value}")
        if (nativeLoaded && _isModelLoaded.value) return true
        if (!nativeLoaded) {
            nativeLoaded = try {
                System.loadLibrary("sherpa-onnx-jni")
                Log.i(TAG, "loadLibrary(sherpa-onnx-jni) SUCCESS")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "loadLibrary FAILED (UnsatisfiedLinkError): ${e.message}")
                false
            } catch (e: Throwable) {
                Log.e(TAG, "loadLibrary FAILED: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            _isAvailable.value = nativeLoaded
        }
        if (nativeLoaded && !_isModelLoaded.value) {
            val zipformer = SherpaModelManager.ModelKind.ASR_ZIPFORMER_BILINGUAL
            val senseVoice = SherpaModelManager.ModelKind.ASR_SENSE_VOICE
            val zipformerPresent = SherpaModelManager.isModelPresent(context, zipformer)
            val senseVoicePresent = SherpaModelManager.isModelPresent(context, senseVoice)
            Log.d(TAG, "Model check: zipformer=$zipformerPresent, senseVoice=$senseVoicePresent")
            if (zipformerPresent) {
                val dir = SherpaModelManager.modelDir(context, zipformer)
                Log.i(TAG, "Loading Zipformer model from ${dir.absolutePath}")
                loadModel(dir.absolutePath)
            } else if (senseVoicePresent) {
                val dir = SherpaModelManager.modelDir(context, senseVoice)
                Log.i(TAG, "Loading SenseVoice model from ${dir.absolutePath}")
                loadSenseVoiceModel(dir.absolutePath)
            } else {
                Log.w(TAG, "No ASR model present, listing model dirs for diagnosis:")
                val base = context.getExternalFilesDir("sherpa_models") ?: File(context.filesDir, "sherpa_models")
                if (base.isDirectory) {
                    base.listFiles()?.forEach { sub ->
                        val files = sub.listFiles()?.map { it.name }?.take(10) ?: emptyList()
                        Log.w(TAG, "  ${sub.name}/ -> ${files}")
                    }
                } else {
                    Log.w(TAG, "  sherpa_models dir does not exist: ${base.absolutePath}")
                }
            }
        }
        Log.d(TAG, "init() result: nativeLoaded=$nativeLoaded, modelLoaded=${_isModelLoaded.value}")
        return nativeLoaded
    }

    fun loadModel(modelDir: String): Boolean {
        if (!nativeLoaded) {
            Log.e(TAG, "loadModel: nativeLoaded=false")
            return false
        }
        val dir = File(modelDir)
        if (!dir.isDirectory) {
            Log.e(TAG, "loadModel: dir not found: $modelDir")
            return false
        }
        val encoder = listOf("encoder-epoch-99-avg-1.int8.onnx", "encoder-epoch-99-avg-1.onnx").firstOrNull { File(dir, it).exists() }
        val decoder = listOf("decoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.int8.onnx").firstOrNull { File(dir, it).exists() }
        val joiner = listOf("joiner-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.onnx").firstOrNull { File(dir, it).exists() }
        if (encoder == null || decoder == null || joiner == null) {
            Log.e(TAG, "loadModel: missing model files. encoder=$encoder, decoder=$decoder, joiner=$joiner")
            Log.e(TAG, "  dir contents: ${dir.listFiles()?.map { it.name }}")
            _lastError.value = "Missing model files in $modelDir"
            return false
        }
        Log.d(TAG, "loadModel: encoder=$encoder, decoder=$decoder, joiner=$joiner")
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
            _lastError.value = null
            Log.i(TAG, "OnlineRecognizer created SUCCESS (threads=${dynamicThreads()}, provider=${bestProvider()})")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "loadModel OnlineRecognizer FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = "OnlineRecognizer init failed: ${e.message}"
            _isModelLoaded.value = false
            false
        }
    }

    fun loadSenseVoiceModel(modelDir: String): Boolean {
        if (!nativeLoaded) {
            Log.e(TAG, "loadSenseVoiceModel: nativeLoaded=false")
            return false
        }
        val dir = File(modelDir)
        if (!dir.isDirectory) {
            Log.e(TAG, "loadSenseVoiceModel: dir not found: $modelDir")
            return false
        }
        val modelFile = listOf("model.int8.onnx", "model.onnx").firstOrNull { File(dir, it).exists() }
        if (modelFile == null) {
            Log.e(TAG, "loadSenseVoiceModel: no model file found. dir contents: ${dir.listFiles()?.map { it.name }}")
            _lastError.value = "No SenseVoice model file in $modelDir"
            return false
        }
        Log.d(TAG, "loadSenseVoiceModel: modelFile=$modelFile")
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
            _lastError.value = null
            Log.i(TAG, "OfflineRecognizer (SenseVoice) created SUCCESS")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "loadSenseVoiceModel OfflineRecognizer FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = "SenseVoice init failed: ${e.message}"
            _isModelLoaded.value = false
            false
        }
    }

    private fun initVadForOffline(context: Context): Boolean {
        val vadDir = SherpaModelManager.modelDir(context, SherpaModelManager.ModelKind.VAD)
        val vadFile = File(vadDir, "silero_vad.onnx")
        if (!vadFile.exists()) {
            Log.e(TAG, "initVadForOffline: silero_vad.onnx not found at ${vadFile.absolutePath}")
            _lastError.value = "VAD model not found"
            return false
        }
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
            Log.i(TAG, "VAD created SUCCESS (threshold=${com.lxseek.chat.util.VoiceRecorder.vadThreshold}, minSilence=${com.lxseek.chat.util.VoiceRecorder.vadMinSilence}, maxSpeech=${com.lxseek.chat.util.VoiceRecorder.vadMaxSpeech})")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "initVadForOffline FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = "VAD init failed: ${e.message}"
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
        Log.i(TAG, "startListening: language=$language, nativeLoaded=$nativeLoaded, modelLoaded=${_isModelLoaded.value}, useOfflineMode=$useOfflineMode")
        if (!nativeLoaded) {
            Log.e(TAG, "startListening: nativeLoaded=false, NOT_AVAILABLE")
            _lastError.value = "Native library not loaded"
            onError(SpeechError.NOT_AVAILABLE); return
        }
        if (!_isModelLoaded.value) {
            Log.e(TAG, "startListening: model not loaded, MODEL_NOT_LOADED")
            _lastError.value = "Model not loaded"
            onError(SpeechError.MODEL_NOT_LOADED); return
        }
        resultCallback = onResult
        errorCallback = onError
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufSize = (minBuf * 2).coerceAtLeast(3200)
            val ar = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "startListening: AudioRecord not initialized (state=${ar.state})")
                ar.release()
                _lastError.value = "AudioRecord init failed"
                onError(SpeechError.AUDIO_CAPTURE)
                return
            }
            audioRecord = ar
            isRecording = true
            _isListening.value = true
            _partialText.value = ""
            ar.startRecording()
            Log.d(TAG, "startListening: AudioRecord started, bufSize=$bufSize")
            if (useOfflineMode) {
                if (!initVadForOffline(context)) {
                    Log.e(TAG, "startListening: VAD init failed for offline mode")
                    onError(SpeechError.MODEL_NOT_LOADED); cleanupAudio(); return
                }
                startOfflineLoop(ar, bufSize)
            } else {
                val rec = onlineRecognizer ?: run {
                    Log.e(TAG, "startListening: onlineRecognizer is null")
                    onError(SpeechError.MODEL_NOT_LOADED); cleanupAudio(); return
                }
                startOnlineLoop(rec, ar, bufSize)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startListening exception: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = "startListening failed: ${e.message}"
            onError(SpeechError.AUDIO_CAPTURE)
            cleanupAudio()
        }
    }

    private fun startOnlineLoop(rec: OnlineRecognizer, ar: AudioRecord, bufSize: Int) {
        recordThread = Thread {
            val stream = rec.createStream()
            val buffer = ShortArray(bufSize)
            var totalRead = 0
            var endpointCount = 0
            Log.d(TAG, "OnlineLoop started")
            try {
                while (isRecording) {
                    val read = ar.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        totalRead += read
                        val samples = FloatArray(read) { buffer[it] / 32768.0f }
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (rec.isReady(stream)) rec.decode(stream)
                        val result = rec.getResult(stream)
                        if (result.text.isNotBlank()) _partialText.value = result.text
                        if (rec.isEndpoint(stream)) {
                            endpointCount++
                            val text = result.text.trim()
                            Log.d(TAG, "OnlineLoop endpoint #$endpointCount: text='$text', totalRead=$totalRead")
                            rec.reset(stream)
                            _partialText.value = ""
                            if (text.isNotEmpty()) {
                                isRecording = false
                                _isListening.value = false
                                Log.i(TAG, "OnlineLoop result: '$text'")
                                mainHandler.post { resultCallback?.invoke(text) }
                                break
                            }
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "OnlineLoop: AudioRecord read error=$read")
                        mainHandler.post { errorCallback?.invoke(SpeechError.AUDIO_CAPTURE) }; break
                    }
                }
                if (isRecording) {
                    Log.w(TAG, "OnlineLoop exited while isRecording=true (totalRead=$totalRead, endpoints=$endpointCount)")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "OnlineLoop exception: ${e.javaClass.simpleName}: ${e.message}", e)
                _lastError.value = "Recognition loop failed: ${e.message}"
                mainHandler.post { errorCallback?.invoke(SpeechError.GENERIC) }
            } finally {
                try { stream.release() } catch (_: Throwable) {}
                Log.d(TAG, "OnlineLoop ended (totalRead=$totalRead, endpoints=$endpointCount)")
            }
        }.also { it.isDaemon = true; it.name = "SherpaAsr-Online" }
        recordThread?.start()
    }

    private fun startOfflineLoop(ar: AudioRecord, bufSize: Int) {
        val rec = offlineRecognizer ?: run {
            Log.e(TAG, "startOfflineLoop: offlineRecognizer is null")
            mainHandler.post { errorCallback?.invoke(SpeechError.MODEL_NOT_LOADED) }; return
        }
        val vadInstance = vad ?: run {
            Log.e(TAG, "startOfflineLoop: vad is null")
            mainHandler.post { errorCallback?.invoke(SpeechError.MODEL_NOT_LOADED) }; return
        }
        recordThread = Thread {
            val windowSize = 512
            val buffer = ShortArray(bufSize)
            val allSamples = ArrayList<Float>()
            val startTime = SystemClock.elapsedRealtime()
            var totalRead = 0
            var segmentCount = 0
            Log.d(TAG, "OfflineLoop started (VAD + SenseVoice)")
            try {
                while (isRecording) {
                    val read = ar.read(buffer, 0, windowSize)
                    if (read > 0) {
                        totalRead += read
                        val samples = FloatArray(read) { buffer[it] / 32768.0f }
                        vadInstance.acceptWaveform(samples)
                        while (!vadInstance.empty()) {
                            val segment = vadInstance.front()
                            segmentCount++
                            allSamples.addAll(segment.samples.toList())
                            Log.d(TAG, "OfflineLoop: VAD segment #$segmentCount, ${segment.samples.size} samples")
                            vadInstance.pop()
                        }
                        if (allSamples.isNotEmpty() && !vadInstance.isSpeechDetected()) {
                            Log.d(TAG, "OfflineLoop: speech ended, ${allSamples.size} samples to recognize")
                            val text = recognizeOffline(rec, allSamples.toFloatArray())
                            allSamples.clear()
                            if (text.isNotEmpty()) {
                                isRecording = false
                                _isListening.value = false
                                Log.i(TAG, "OfflineLoop result: '$text'")
                                mainHandler.post { resultCallback?.invoke(text) }
                                break
                            }
                        }
                        if (SystemClock.elapsedRealtime() - startTime > 30_000L) {
                            Log.w(TAG, "OfflineLoop: 30s timeout, ${allSamples.size} samples")
                            if (allSamples.isNotEmpty()) {
                                val text = recognizeOffline(rec, allSamples.toFloatArray())
                                allSamples.clear()
                                if (text.isNotEmpty()) {
                                    isRecording = false
                                    _isListening.value = false
                                    Log.i(TAG, "OfflineLoop result (timeout): '$text'")
                                    mainHandler.post { resultCallback?.invoke(text) }
                                    break
                                }
                            }
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "OfflineLoop: AudioRecord read error=$read")
                        mainHandler.post { errorCallback?.invoke(SpeechError.AUDIO_CAPTURE) }; break
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "OfflineLoop exception: ${e.javaClass.simpleName}: ${e.message}", e)
                _lastError.value = "Recognition loop failed: ${e.message}"
                mainHandler.post { errorCallback?.invoke(SpeechError.GENERIC) }
            }
            Log.d(TAG, "OfflineLoop ended (totalRead=$totalRead, segments=$segmentCount)")
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
            Log.d(TAG, "recognizeOffline: ${samples.size} samples -> '${result.text}'")
            result.text.trim()
        } catch (e: Throwable) {
            Log.e(TAG, "recognizeOffline failed: ${e.javaClass.simpleName}: ${e.message}", e)
            ""
        }
    }

    override fun stopListening() {
        Log.d(TAG, "stopListening")
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
        Log.d(TAG, "shutdown")
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

    fun getDiagnosticText(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== SherpaAsrEngine Diagnostics ===\n")
        sb.append("nativeLoaded: $nativeLoaded\n")
        sb.append("isAvailable: ${_isAvailable.value}\n")
        sb.append("isModelLoaded: ${_isModelLoaded.value}\n")
        sb.append("useOfflineMode: $useOfflineMode\n")
        sb.append("lastError: ${_lastError.value}\n")
        sb.append("isListening: ${_isListening.value}\n")
        sb.append("partialText: '${_partialText.value}'\n")
        sb.append("onlineRecognizer: ${onlineRecognizer != null}\n")
        sb.append("offlineRecognizer: ${offlineRecognizer != null}\n")
        sb.append("vad: ${vad != null}\n")
        sb.append("threads: ${dynamicThreads()}\n")
        sb.append("\n=== Model Status ===\n")
        val base = context.getExternalFilesDir("sherpa_models") ?: File(context.filesDir, "sherpa_models")
        sb.append("baseDir: ${base.absolutePath}\n")
        sb.append("baseDir exists: ${base.isDirectory}\n")
        for (kind in SherpaModelManager.ModelKind.entries) {
            val dir = SherpaModelManager.modelDir(context, kind)
            val present = SherpaModelManager.isModelPresent(context, kind)
            sb.append("\n[${kind.name}] present=$present\n")
            sb.append("  dir: ${dir.absolutePath}\n")
            if (dir.isDirectory) {
                val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (f in files) {
                    sb.append("  ${f.name} (${f.length()} bytes)\n")
                }
            } else {
                sb.append("  (dir not found)\n")
            }
        }
        sb.append("\n=== VAD Params ===\n")
        sb.append("threshold: ${com.lxseek.chat.util.VoiceRecorder.vadThreshold}\n")
        sb.append("minSilence: ${com.lxseek.chat.util.VoiceRecorder.vadMinSilence}\n")
        sb.append("maxSpeech: ${com.lxseek.chat.util.VoiceRecorder.vadMaxSpeech}\n")
        return sb.toString()
    }
}
