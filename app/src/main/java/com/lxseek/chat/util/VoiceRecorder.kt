package com.lxseek.chat.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val SILENCE_THRESHOLD_DB = 65.0
private const val SILENCE_AFTER_SPEECH_MS = 2000L
private const val MAX_RECORDING_MS = 60_000L
private const val MIN_SPEECH_MS = 300L
private const val SAMPLE_INTERVAL_MS = 100L

class VoiceRecorder {

    enum class RecordingState { IDLE, RECORDING, STOPPING }

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var isRecording = false
    @Volatile private var outputFile: File? = null
    private var vadThread: Thread? = null
    private var startTimeMs = 0L
    private var onComplete: ((File) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(
        context: Context,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isRecording) return
        this.onComplete = onComplete
        this.onError = onError
        val file = File(context.cacheDir, "voice_record_${System.currentTimeMillis()}.m4a")
        outputFile = file
        try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16000)
            rec.setAudioChannels(1)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            isRecording = true
            startTimeMs = SystemClock.elapsedRealtime()
            _state.value = RecordingState.RECORDING
            startVadMonitoring()
        } catch (e: Exception) {
            cleanup()
            onError("Failed to start recording: ${e.message}")
        }
    }

    private fun startVadMonitoring() {
        var speechDetected = false
        var firstSpeechTime = 0L
        var lastSpeechTime = 0L
        var smoothedDb = 0.0
        val lpfRatio = 0.6

        vadThread = Thread {
            while (isRecording) {
                try {
                    val rec = recorder ?: break
                    val maxAmp = rec.maxAmplitude
                    if (maxAmp > 1) {
                        val db = 20.0 * Math.log10(maxAmp.toDouble())
                        smoothedDb = (1 - lpfRatio) * smoothedDb + lpfRatio * db
                        _amplitude.value = (smoothedDb / 100.0).toFloat().coerceIn(0f, 1f)

                        if (smoothedDb > SILENCE_THRESHOLD_DB) {
                            if (!speechDetected) {
                                speechDetected = true
                                firstSpeechTime = SystemClock.elapsedRealtime()
                            }
                            lastSpeechTime = SystemClock.elapsedRealtime()
                        }

                        val now = SystemClock.elapsedRealtime()
                        if (speechDetected &&
                            smoothedDb < SILENCE_THRESHOLD_DB &&
                            now - lastSpeechTime > SILENCE_AFTER_SPEECH_MS &&
                            now - firstSpeechTime > MIN_SPEECH_MS
                        ) {
                            stopInternal(autoStop = true)
                            break
                        }
                        if (now - startTimeMs > MAX_RECORDING_MS) {
                            stopInternal(autoStop = true)
                            break
                        }
                    }
                } catch (_: Exception) {
                }
                SystemClock.sleep(SAMPLE_INTERVAL_MS)
            }
        }.also { it.isDaemon = true; it.name = "VoiceRecorder-VAD" }
        vadThread?.start()
    }

    fun stop() {
        stopInternal(autoStop = false)
    }

    private fun stopInternal(autoStop: Boolean) {
        if (!isRecording) return
        isRecording = false
        _state.value = RecordingState.STOPPING
        val file = outputFile
        val callback = onComplete
        mainHandler.post {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            recorder = null
            _amplitude.value = 0f
            _state.value = RecordingState.IDLE
            if (autoStop && file != null && file.exists() && file.length() > 0) {
                callback?.invoke(file)
            }
        }
    }

    private fun cleanup() {
        isRecording = false
        _state.value = RecordingState.IDLE
        _amplitude.value = 0f
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
