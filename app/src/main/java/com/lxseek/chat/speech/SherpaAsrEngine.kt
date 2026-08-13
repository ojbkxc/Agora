package com.lxseek.chat.speech

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stub implementation for sherpa-onnx ASR engine.
 *
 * This gracefully degrades when the native library (libsherpa-onnx-jni.so) is not present.
 * When the library is added to jniLibs and the sherpa-onnx Kotlin API source files are
 * included, this class will be replaced with a full implementation that uses
 * OnlineRecognizer for streaming recognition and OfflineRecognizer for batch processing.
 *
 * Detection: attempts to load the native library via System.loadLibrary. If it fails,
 * [isAvailable] remains false and the engine is skipped by [SpeechRecognitionManager].
 */
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
        // Full implementation requires sherpa-onnx Kotlin API source files.
        // When added, this will use OnlineRecognizer for streaming recognition.
        onError(SpeechError.NOT_AVAILABLE)
    }

    override fun stopListening() {
        _isListening.value = false
        _partialText.value = ""
    }

    override fun shutdown() {
        stopListening()
        _isAvailable.value = false
        _isModelLoaded.value = false
        nativeLoaded = false
    }

    /**
     * Checks if a model is present at the given path and marks it as loaded.
     * Called by [SpeechRecognitionManager] after model download completes.
     */
    fun loadModel(modelPath: String): Boolean {
        if (!nativeLoaded) return false
        // Full implementation will create OnlineRecognizer/OfflineRecognizer instances.
        // For now, just mark as loaded if the path is non-empty.
        val loaded = modelPath.isNotBlank()
        _isModelLoaded.value = loaded
        return loaded
    }
}
