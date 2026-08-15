package com.lxseek.chat.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * On-device TTS engine backed by sherpa-onnx (Kokoro or Piper/VITS).
 * Bypasses the system TextToSpeech engine entirely, avoiding ROM-specific
 * limitations (MIUI/EMUI bindService restrictions).
 */
object SherpaTtsEngine {

    enum class ModelType { KOKORO, VITS }

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile private var nativeLoaded = false
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var stopped = false
    @Volatile private var speakThread: Thread? = null

    fun init(context: Context): Boolean {
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
     * Loads a TTS model from [modelDir].
     * For KOKORO: expects model.onnx, voices.bin, tokens.txt, dataDir (espeak-ng-data).
     * For VITS (Piper): expects model.onnx, tokens.txt, espeak-ng-data dir.
     * Returns true on success.
     */
    fun loadModel(modelDir: String, modelType: ModelType, dataDir: String = ""): Boolean {
        if (!nativeLoaded) return false
        val dir = File(modelDir)
        if (!dir.isDirectory) return false
        return try {
            tts?.release()
            val config = when (modelType) {
                ModelType.KOKORO -> OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = "${dir.absolutePath}/model.onnx",
                            voices = "${dir.absolutePath}/voices.bin",
                            tokens = "${dir.absolutePath}/tokens.txt",
                            dataDir = dataDir.ifBlank { "${dir.absolutePath}/espeak-ng-data" },
                            lengthScale = 1.0f,
                        ),
                        numThreads = 2,
                    ),
                )
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
            _isModelLoaded.value = true
            true
        } catch (_: Throwable) {
            _isModelLoaded.value = false
            false
        }
    }

    /**
     * Synthesizes [text] to speech and plays it via AudioTrack.
     * Returns true if synthesis started successfully.
     */
    fun speak(text: String, sid: Int = 0, speed: Float = 1.0f): Boolean {
        val ttsInstance = tts
        if (ttsInstance == null || !_isModelLoaded.value) return false
        stop()
        stopped = false
        _isPlaying.value = true
        speakThread = Thread {
            try {
                val sampleRate = ttsInstance.sampleRate()
                ensureAudioTrack(sampleRate)
                val track = audioTrack ?: run { _isPlaying.value = false; return@Thread }
                track.play()
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
                        } catch (_: Throwable) {
                            0
                        }
                    }
                }
            } catch (_: Throwable) {
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
            bufLength * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    fun stop() {
        stopped = true
        try { audioTrack?.stop() } catch (_: Throwable) {}
        _isPlaying.value = false
        speakThread?.let { t ->
            try { t.join(200) } catch (_: Throwable) {}
        }
        speakThread = null
    }

    fun shutdown() {
        stop()
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
        try { tts?.release() } catch (_: Throwable) {}
        tts = null
        _isAvailable.value = false
        _isModelLoaded.value = false
        nativeLoaded = false
    }
}
