package com.lxseek.chat.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.lxseek.chat.util.AppLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

private const val VOSK_TAG = "VoskAsrEngine"
private const val SAMPLE_RATE = 16000
private const val MAX_RECORDING_MS = 30_000L

/**
 * Vosk-based offline ASR engine.
 *
 * Uses org.vosk.Model + org.vosk.Recognizer with AudioRecord 16kHz mono PCM.
 * Models stored in context.filesDir/vosk/model-$lang/ (managed by VoskModelManager).
 */
class VoskAsrEngine : SpeechEngine {

    override val id: String = "vosk"
    override val displayName: String = "Vosk (Offline)"

    private val _isAvailable = MutableStateFlow(true)
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

    @Volatile private var initialized = false
    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var currentModelLang: String? = null

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recognizeJob: Job? = null

    override fun init(context: Context): Boolean {
        if (initialized) return true
        return try {
            Log.i(VOSK_TAG, "init: Vosk engine (native lib bundled in AAR)")
            initialized = true
            _isAvailable.value = true
            checkDownloadedModel(context)
            true
        } catch (e: Throwable) {
            Log.e(VOSK_TAG, "init failed: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = e.message
            _isAvailable.value = false
            false
        }
    }

    private fun checkDownloadedModel(context: Context) {
        val downloaded = VoskModelManager.getDownloadedModels(context)
        _isModelLoaded.value = downloaded.isNotEmpty()
        if (downloaded.isNotEmpty()) {
            Log.i(VOSK_TAG, "Found downloaded Vosk models: $downloaded")
        }
    }

    private fun resolveLanguageCode(language: String): String = when (language) {
        "en" -> "en"
        "zh" -> "zh"
        "system", "" -> {
            val locale = Locale.getDefault()
            when (locale.language) {
                "zh" -> "zh"
                "en" -> "en"
                else -> "en"
            }
        }
        else -> language
    }

    private fun ensureModelLoaded(context: Context, language: String): Boolean {
        val code = resolveLanguageCode(language)
        if (model != null && currentModelLang == code) return true

        return try {
            model?.close()
            model = null
            currentModelLang = null
            _isModelLoaded.value = false

            if (!VoskModelManager.isModelDownloaded(context, code)) {
                val fallback = listOf("en", "zh").firstOrNull { VoskModelManager.isModelDownloaded(context, it) }
                if (fallback != null) {
                    Log.i(VOSK_TAG, "Model $code not found, using fallback: $fallback")
                    val dir = VoskModelManager.getModelDir(context, fallback)
                    model = Model(dir.absolutePath)
                    currentModelLang = fallback
                } else {
                    Log.w(VOSK_TAG, "No Vosk model downloaded for $code or fallback")
                    _lastError.value = "No Vosk model downloaded"
                    return false
                }
            } else {
                val dir = VoskModelManager.getModelDir(context, code)
                Log.i(VOSK_TAG, "Loading Vosk model from ${dir.absolutePath}")
                model = Model(dir.absolutePath)
                currentModelLang = code
            }
            _isModelLoaded.value = true
            _lastError.value = null
            true
        } catch (e: Throwable) {
            Log.e(VOSK_TAG, "Failed to load Vosk model: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = e.message
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
        if (_isListening.value) {
            Log.w(VOSK_TAG, "startListening: already listening")
            return
        }
        if (!ensureModelLoaded(context, language)) {
            onError(SpeechError.MODEL_NOT_LOADED)
            return
        }
        val currentModel = model ?: run {
            onError(SpeechError.MODEL_NOT_LOADED)
            return
        }
        try {
            recognizer?.close()
            recognizer = Recognizer(currentModel, SAMPLE_RATE.toFloat())
            recognizer!!.setWords(true)

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBuf * 2).coerceAtLeast(3200)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(VOSK_TAG, "AudioRecord init failed")
                _lastError.value = "AudioRecord init failed"
                onError(SpeechError.AUDIO_CAPTURE)
                return
            }
            audioRecord!!.startRecording()
            _isListening.value = true
            _partialText.value = ""
            Log.i(VOSK_TAG, "startListening: Vosk recognition started (lang=$currentModelLang, bufferSize=$bufferSize)")

            recognizeJob?.cancel()
            recognizeJob = engineScope.launch {
                val shortBuffer = ShortArray(bufferSize / 2)
                val startTime = System.currentTimeMillis()
                var lastResultText = ""
                while (isActive && _isListening.value) {
                    val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1
                    if (read <= 0) continue
                    val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) byteBuffer.putShort(shortBuffer[i])
                    val bytes = byteBuffer.array()
                    val rec = recognizer ?: break
                    try {
                        val isEndpoint = rec.acceptWaveForm(bytes, bytes.size)
                        val partialJson = rec.partialResult
                        val partial = JSONObject(partialJson).optString("partial", "")
                        if (partial.isNotBlank()) _partialText.value = partial

                        if (isEndpoint) {
                            val resultJson = rec.result
                            val text = JSONObject(resultJson).optString("text", "").trim()
                            if (text.isNotBlank()) {
                                lastResultText = text
                                Log.i(VOSK_TAG, "Endpoint result: '$text'")
                                stopListening()
                                onResult(text)
                                return@launch
                            }
                            rec.reset()
                        }
                        if (System.currentTimeMillis() - startTime > MAX_RECORDING_MS) {
                            val finalJson = rec.finalResult
                            val finalText = JSONObject(finalJson).optString("text", "").trim()
                            Log.i(VOSK_TAG, "Timeout final result: '$finalText' (lastPartial='${_partialText.value}')")
                            stopListening()
                            val result = finalText.ifBlank { _partialText.value.trim() }
                            if (result.isNotBlank()) onResult(result)
                            else onError(SpeechError.NO_MATCH)
                            return@launch
                        }
                    } catch (e: Throwable) {
                        Log.e(VOSK_TAG, "Recognition loop error: ${e.message}", e)
                        stopListening()
                        onError(SpeechError.GENERIC)
                        return@launch
                    }
                }
                if (!isActive) return@launch
                val rec = recognizer
                if (rec != null) {
                    try {
                        val finalJson = rec.finalResult
                        val finalText = JSONObject(finalJson).optString("text", "").trim()
                        val result = finalText.ifBlank { lastResultText.ifBlank { _partialText.value.trim() } }
                        Log.i(VOSK_TAG, "Loop exit final: '$finalText', result='$result'")
                        if (result.isNotBlank()) onResult(result)
                        else onError(SpeechError.NO_MATCH)
                    } catch (e: Throwable) {
                        Log.e(VOSK_TAG, "Final result error: ${e.message}", e)
                        onError(SpeechError.GENERIC)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(VOSK_TAG, "startListening crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            _lastError.value = e.message
            _isListening.value = false
            onError(SpeechError.GENERIC)
        }
    }

    override fun stopListening() {
        _isListening.value = false
        recognizeJob?.cancel()
        recognizeJob = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
        _partialText.value = ""
    }

    override fun shutdown() {
        stopListening()
        try { model?.close() } catch (_: Throwable) {}
        model = null
        currentModelLang = null
        initialized = false
        _isModelLoaded.value = false
        _lastError.value = null
        engineScope.cancel()
    }

    fun getDiagnosticText(context: Context): String = buildString {
        appendLine("=== Vosk ASR Engine ===")
        appendLine("initialized: $initialized")
        appendLine("available: ${_isAvailable.value}")
        appendLine("modelLoaded: ${_isModelLoaded.value}")
        appendLine("currentModelLang: $currentModelLang")
        appendLine("isListening: ${_isListening.value}")
        appendLine("lastError: ${_lastError.value ?: "none"}")
        appendLine()
        append(VoskModelManager.getDiagnosticText(context))
    }
}
