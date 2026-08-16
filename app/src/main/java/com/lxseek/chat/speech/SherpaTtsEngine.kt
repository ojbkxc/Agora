package com.lxseek.chat.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.lxseek.chat.util.AppLog as Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val TAG = "SherpaTtsEngine"

object SherpaTtsEngine {

    enum class ModelType { KOKORO, VITS }

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile private var nativeLoaded = false
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var stopped = false
    @Volatile private var speakThread: Thread? = null
    @Volatile private var currentModelType: ModelType? = null

    fun init(context: Context): Boolean {
        Log.d(TAG, "init() called, nativeLoaded=$nativeLoaded, modelLoaded=${_isModelLoaded.value}")
        if (nativeLoaded && _isModelLoaded.value) return true
        if (!nativeLoaded) {
            nativeLoaded = try {
                System.loadLibrary("sherpa-onnx-jni")
                Log.i(TAG, "loadLibrary SUCCESS")
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
            for (kind in listOf(
                SherpaModelManager.ModelKind.TTS_KOKORO,
                SherpaModelManager.ModelKind.TTS_KOKORO_V1_1,
                SherpaModelManager.ModelKind.TTS_KOKORO_INT8_V1_1,
            )) {
                val present = SherpaModelManager.isModelPresent(context, kind)
                Log.d(TAG, "TTS model check: ${kind.name} present=$present")
                if (present) {
                    val dir = SherpaModelManager.modelDir(context, kind)
                    Log.i(TAG, "Loading TTS model ${kind.name} from ${dir.absolutePath}")
                    loadModel(dir.absolutePath, ModelType.KOKORO)
                    break
                }
            }
        }
        Log.d(TAG, "init() result: nativeLoaded=$nativeLoaded, modelLoaded=${_isModelLoaded.value}")
        return nativeLoaded
    }

    fun loadModel(modelDir: String, modelType: ModelType, dataDir: String = ""): Boolean {
        if (!nativeLoaded) {
            Log.e(TAG, "loadModel: nativeLoaded=false")
            return false
        }
        val dir = File(modelDir)
        if (!dir.isDirectory) {
            Log.e(TAG, "loadModel: dir not found: $modelDir")
            return false
        }
        Log.d(TAG, "loadModel: modelDir=$modelDir, type=$modelType")
        return try {
            tts?.release()
            val config = when (modelType) {
                ModelType.KOKORO -> {
                    val modelFile = listOf("model.int8.onnx", "model.onnx").firstOrNull { File(dir, it).exists() } ?: "model.onnx"
                    val lexiconParts = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
                        .filter { File(dir, it).exists() }
                        .joinToString(",") { "${dir.absolutePath}/$it" }
                    val ruleFstParts = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst")
                        .filter { File(dir, it).exists() }
                        .joinToString(",") { "${dir.absolutePath}/$it" }
                    Log.d(TAG, "Kokoro config: model=$modelFile, lexicon=$lexiconParts, ruleFsts=$ruleFstParts")
                    OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            kokoro = OfflineTtsKokoroModelConfig(
                                model = "${dir.absolutePath}/$modelFile",
                                voices = "${dir.absolutePath}/voices.bin",
                                tokens = "${dir.absolutePath}/tokens.txt",
                                dataDir = dataDir.ifBlank { "${dir.absolutePath}/espeak-ng-data" },
                                lexicon = lexiconParts,
                                lengthScale = 1.0f,
                            ),
                            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                            provider = "cpu",
                        ),
                        ruleFsts = ruleFstParts,
                    )
                }
                ModelType.VITS -> OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = "${dir.absolutePath}/model.onnx",
                            tokens = "${dir.absolutePath}/tokens.txt",
                            dataDir = dataDir.ifBlank { "${dir.absolutePath}/espeak-ng-data" },
                            lengthScale = 1.0f,
                        ),
                        numThreads = 2,
                    ),
                )
            }
            tts = OfflineTts(config = config)
            currentModelType = modelType
            _isModelLoaded.value = true
            _lastError.value = null
            Log.i(TAG, "TTS model loaded SUCCESS (type=$modelType, sampleRate=${tts?.sampleRate()})")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "loadModel FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = "TTS loadModel failed: ${e.message}"
            _isModelLoaded.value = false
            false
        }
    }

    fun speak(text: String, sid: Int = 0, speed: Float = 1.0f): Boolean {
        val ttsInstance = tts
        if (ttsInstance == null || !_isModelLoaded.value) {
            Log.e(TAG, "speak: tts=null or model not loaded")
            return false
        }
        Log.d(TAG, "speak: text='${text.take(50)}', sid=$sid, speed=$speed")
        stop()
        stopped = false
        _isPlaying.value = true
        speakThread = Thread {
            try {
                val sampleRate = ttsInstance.sampleRate()
                ensureAudioTrack(sampleRate)
                val track = audioTrack ?: run {
                    Log.e(TAG, "speak: audioTrack null after ensureAudioTrack")
                    _isPlaying.value = false; return@Thread
                }
                track.play()
                Log.d(TAG, "speak: AudioTrack playing, sampleRate=$sampleRate")
                ttsInstance.generateWithCallback(
                    text = text,
                    sid = sid,
                    speed = speed,
                ) { samples ->
                    if (stopped) {
                        0
                    } else {
                        try {
                            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                            1
                        } catch (e: Throwable) {
                            Log.e(TAG, "AudioTrack.write failed: ${e.message}")
                            0
                        }
                    }
                }
                Log.d(TAG, "speak: generation completed")
            } catch (e: Throwable) {
                Log.e(TAG, "speak exception: ${e.javaClass.simpleName}: ${e.message}", e)
                _lastError.value = "TTS speak failed: ${e.message}"
            } finally {
                try { audioTrack?.stop() } catch (_: Throwable) {}
                _isPlaying.value = false
            }
        }.also { it.isDaemon = true; it.name = "SherpaTtsEngine-Speak" }
        speakThread?.start()
        return true
    }

    private fun ensureAudioTrack(sampleRate: Int) {
        if (audioTrack != null) {
            try { audioTrack?.release() } catch (_: Throwable) {}
            audioTrack = null
        }
        val bufLength = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val attr = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()
        audioTrack = AudioTrack(
            attr,
            format,
            bufLength * 4,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    fun stop() {
        Log.d(TAG, "stop")
        stopped = true
        try { audioTrack?.stop() } catch (_: Throwable) {}
        _isPlaying.value = false
        speakThread?.let { t ->
            try { t.join(200) } catch (_: Throwable) {}
        }
        speakThread = null
    }

    fun shutdown() {
        Log.d(TAG, "shutdown")
        stop()
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        try { tts?.release() } catch (_: Throwable) {}
        tts = null
        _isAvailable.value = false
        _isModelLoaded.value = false
        nativeLoaded = false
    }

    fun getDiagnosticText(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== SherpaTtsEngine Diagnostics ===\n")
        sb.append("nativeLoaded: $nativeLoaded\n")
        sb.append("isAvailable: ${_isAvailable.value}\n")
        sb.append("isModelLoaded: ${_isModelLoaded.value}\n")
        sb.append("isPlaying: ${_isPlaying.value}\n")
        sb.append("currentModelType: $currentModelType\n")
        sb.append("lastError: ${_lastError.value}\n")
        sb.append("tts: ${tts != null}\n")
        sb.append("audioTrack: ${audioTrack != null}\n")
        sb.append("\n=== TTS Model Status ===\n")
        for (kind in listOf(
            SherpaModelManager.ModelKind.TTS_KOKORO,
            SherpaModelManager.ModelKind.TTS_KOKORO_V1_1,
            SherpaModelManager.ModelKind.TTS_KOKORO_INT8_V1_1,
        )) {
            val dir = SherpaModelManager.modelDir(context, kind)
            val present = SherpaModelManager.isModelPresent(context, kind)
            sb.append("\n[${kind.name}] present=$present\n")
            if (dir.isDirectory) {
                val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (f in files.take(20)) {
                    sb.append("  ${f.name} (${f.length()} bytes)\n")
                }
                if (files.size > 20) sb.append("  ... and ${files.size - 20} more files\n")
            } else {
                sb.append("  (dir not found)\n")
            }
        }
        return sb.toString()
    }
}
