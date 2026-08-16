package com.lxseek.chat.speech

import android.content.Context
import com.lxseek.chat.util.AppLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val VOSK_TAG = "VoskAsrEngine"

/**
 * Vosk-based offline ASR engine — stub for Phase 2 (wiring).
 *
 * Phase 3 will implement full logic: Model loading, Recognizer loop,
 * AudioRecord 16kHz capture, partial/final results via Vosk SpeechRecognizer service.
 *
 * Vosk native library is bundled in the AAR (com.alphacephei:vosk-android:0.3.47),
 * so [isAvailable] is always true once [init] succeeds. Model must be downloaded
 * separately (stored in context.filesDir/vosk/model-$lang/).
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
        val voskDir = java.io.File(context.filesDir, "vosk")
        if (!voskDir.exists()) {
            _isModelLoaded.value = false
            return
        }
        val modelDirs = voskDir.listFiles { f -> f.isDirectory && f.name.startsWith("model-") }
        if (modelDirs.isNullOrEmpty()) {
            _isModelLoaded.value = false
            return
        }
        val hasModel = modelDirs.any { dir ->
            java.io.File(dir, "am/final.mdl").exists() ||
            java.io.File(dir, "am/model.mdl").exists() ||
            dir.walkTopDown().any { it.isFile && it.name.endsWith(".mdl") }
        }
        _isModelLoaded.value = hasModel
        if (hasModel) {
            Log.i(VOSK_TAG, "Found downloaded model(s): ${modelDirs.map { it.name }}")
        }
    }

    override fun startListening(
        context: Context,
        language: String,
        onResult: (String) -> Unit,
        onError: (Int) -> Unit,
    ) {
        if (!_isModelLoaded.value) {
            Log.w(VOSK_TAG, "startListening: no model loaded — download a Vosk model in settings first")
            _lastError.value = "No Vosk model downloaded"
            onError(SpeechError.MODEL_NOT_LOADED)
            return
        }
        Log.w(VOSK_TAG, "startListening: Vosk recognition not yet implemented (Phase 3)")
        _lastError.value = "Vosk recognition not yet implemented"
        onError(SpeechError.GENERIC)
    }

    override fun stopListening() {
        _isListening.value = false
        _partialText.value = ""
    }

    override fun shutdown() {
        stopListening()
        initialized = false
        _isModelLoaded.value = false
        _lastError.value = null
    }

    fun getDiagnosticText(): String = buildString {
        appendLine("=== Vosk ASR Engine ===")
        appendLine("initialized: $initialized")
        appendLine("available: ${_isAvailable.value}")
        appendLine("modelLoaded: ${_isModelLoaded.value}")
        appendLine("lastError: ${_lastError.value ?: "none"}")
    }
}
