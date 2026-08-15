package com.lxseek.chat.util

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val SILENCE_THRESHOLD_RMS = 800.0
private const val SILENCE_AFTER_SPEECH_MS = 1500L
private const val MAX_RECORDING_MS = 30_000L
private const val MIN_SPEECH_MS = 300L

class VoiceRecorder {

    enum class RecordingState { IDLE, RECORDING, STOPPING }

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var vad: Vad? = null
    @Volatile private var useSileroVad = false
    private var recordThread: Thread? = null
    private var startTimeMs = 0L
    private var onComplete: ((File) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Initializes Silero VAD with the model at [modelPath] (silero_vad.onnx).
     * Returns true on success. When false, [start] falls back to RMS amplitude VAD.
     */
    fun initSileroVad(modelPath: String): Boolean {
        if (modelPath.isBlank()) return false
        return try {
            vad?.release()
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelPath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 8.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            )
            vad = Vad(config = config)
            useSileroVad = true
            true
        } catch (_: Throwable) {
            vad = null
            useSileroVad = false
            false
        }
    }

    fun start(
        context: Context,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isRecording) return
        if (!useSileroVad) {
            val vadFile = File(context.getExternalFilesDir("sherpa_models"), "vad/silero_vad.onnx")
            if (vadFile.exists()) initSileroVad(vadFile.absolutePath)
        }
        this.onComplete = onComplete
        this.onError = onError
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
                onError("Failed to initialize AudioRecord")
                return
            }
            audioRecord = ar
            isRecording = true
            startTimeMs = SystemClock.elapsedRealtime()
            _state.value = RecordingState.RECORDING
            ar.startRecording()
            startRecordingLoop(ar, bufSize)
        } catch (e: Exception) {
            cleanup()
            onError("Failed to start recording: ${e.message}")
        }
    }

    private fun startRecordingLoop(ar: AudioRecord, bufSize: Int) {
        recordThread = Thread {
            val buffer = ShortArray(bufSize)
            val pcmCollector = ByteArrayOutputStream()
            if (useSileroVad) {
                runSileroVadLoop(ar, buffer, pcmCollector)
            } else {
                runRmsVadLoop(ar, buffer, pcmCollector)
            }
        }.also { it.isDaemon = true; it.name = "VoiceRecorder-Loop" }
        recordThread?.start()
    }

    private fun runSileroVadLoop(ar: AudioRecord, buffer: ShortArray, pcmCollector: ByteArrayOutputStream) {
        val vadInstance = vad ?: return
        val windowSize = 512
        try {
            while (isRecording) {
                val read = ar.read(buffer, 0, windowSize)
                if (read > 0) {
                    val samples = FloatArray(read) { buffer[it] / 32768.0f }
                    _amplitude.value = computeAmplitude(samples)
                    vadInstance.acceptWaveform(samples)
                    while (!vadInstance.empty()) {
                        val segment = vadInstance.front()
                        pcmCollector.write(toPcmBytes(segment.samples))
                        vadInstance.pop()
                    }
                    if (pcmCollector.size() > 0 && !vadInstance.isSpeechDetected()) {
                        finishWithPcm(pcmCollector)
                        return
                    }
                    if (SystemClock.elapsedRealtime() - startTimeMs > MAX_RECORDING_MS) {
                        if (pcmCollector.size() > 0) finishWithPcm(pcmCollector)
                        else stopInternal(autoStop = false)
                        return
                    }
                } else if (read < 0) {
                    mainHandler.post { onError?.invoke("AudioRecord read error: $read") }
                    stopInternal(autoStop = false)
                    return
                }
            }
        } catch (_: Throwable) {
            mainHandler.post { onError?.invoke("Silero VAD loop failed") }
            stopInternal(autoStop = false)
        }
    }

    private fun runRmsVadLoop(ar: AudioRecord, buffer: ShortArray, pcmCollector: ByteArrayOutputStream) {
        var speechDetected = false
        var firstSpeechTime = 0L
        var lastSpeechTime = 0L
        try {
            while (isRecording) {
                val read = ar.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val samples = FloatArray(read) { buffer[it] / 32768.0f }
                    val rms = computeRms(samples)
                    _amplitude.value = (rms / 32768.0f).toFloat().coerceIn(0f, 1f)
                    pcmCollector.write(toPcmBytes(samples))
                    if (rms > SILENCE_THRESHOLD_RMS) {
                        if (!speechDetected) {
                            speechDetected = true
                            firstSpeechTime = SystemClock.elapsedRealtime()
                        }
                        lastSpeechTime = SystemClock.elapsedRealtime()
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (speechDetected &&
                        rms < SILENCE_THRESHOLD_RMS &&
                        now - lastSpeechTime > SILENCE_AFTER_SPEECH_MS &&
                        now - firstSpeechTime > MIN_SPEECH_MS
                    ) {
                        finishWithPcm(pcmCollector)
                        return
                    }
                    if (now - startTimeMs > MAX_RECORDING_MS) {
                        finishWithPcm(pcmCollector)
                        return
                    }
                } else if (read < 0) {
                    mainHandler.post { onError?.invoke("AudioRecord read error: $read") }
                    stopInternal(autoStop = false)
                    return
                }
            }
        } catch (_: Throwable) {
            mainHandler.post { onError?.invoke("RMS VAD loop failed") }
            stopInternal(autoStop = false)
        }
    }

    private fun finishWithPcm(pcmCollector: ByteArrayOutputStream) {
        isRecording = false
        _state.value = RecordingState.STOPPING
        val pcmBytes = pcmCollector.toByteArray()
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        _amplitude.value = 0f
        _state.value = RecordingState.IDLE
        if (pcmBytes.size > 0) {
            val file = File.createTempFile("voice_record", ".wav")
            writeWavFile(file, pcmBytes)
            mainHandler.post { onComplete?.invoke(file) }
        }
    }

    private fun computeAmplitude(samples: FloatArray): Float {
        var max = 0f
        for (s in samples) { val a = kotlin.math.abs(s); if (a > max) max = a }
        return max.coerceIn(0f, 1f)
    }

    private fun computeRms(samples: FloatArray): Double {
        var sum = 0.0
        for (s in samples) { sum += (s * 32768.0) * (s * 32768.0) }
        return Math.sqrt(sum / samples.size)
    }

    private fun toPcmBytes(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i] * 32768.0f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun writeWavFile(file: File, pcmBytes: ByteArray) {
        FileOutputStream(file).use { out ->
            val totalDataLen = pcmBytes.size
            val byteRate = SAMPLE_RATE * 2
            out.write("RIFF".toByteArray())
            out.write(intToLittleEndian(36 + totalDataLen))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(intToLittleEndian(16))
            out.write(shortToLittleEndian(1))
            out.write(shortToLittleEndian(1))
            out.write(intToLittleEndian(SAMPLE_RATE))
            out.write(intToLittleEndian(byteRate))
            out.write(shortToLittleEndian(2))
            out.write(shortToLittleEndian(16))
            out.write("data".toByteArray())
            out.write(intToLittleEndian(totalDataLen))
            out.write(pcmBytes)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

    private fun shortToLittleEndian(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        )

    fun stop() {
        stopInternal(autoStop = false)
    }

    private fun stopInternal(autoStop: Boolean) {
        if (!isRecording) return
        isRecording = false
        _state.value = RecordingState.STOPPING
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        _amplitude.value = 0f
        _state.value = RecordingState.IDLE
    }

    private fun cleanup() {
        isRecording = false
        _state.value = RecordingState.IDLE
        _amplitude.value = 0f
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    fun releaseVad() {
        try { vad?.release() } catch (_: Throwable) {}
        vad = null
        useSileroVad = false
    }
}
