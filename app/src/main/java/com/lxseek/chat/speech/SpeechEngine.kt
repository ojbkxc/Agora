package com.lxseek.chat.speech

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over ASR (Automatic Speech Recognition) engines.
 *
 * Implementations:
 * - [SystemSpeechEngine] — wraps Android system SpeechRecognizer (online/cloud, always available)
 * - [SherpaAsrEngine] — wraps sherpa-onnx (offline, on-device, requires native lib + model)
 *
 * The [SpeechRecognitionManager] selects the best available engine at runtime.
 */
interface SpeechEngine {

    /** Engine identifier: "system", "sherpa-onnx", etc. */
    val id: String

    /** Human-readable name for settings UI. */
    val displayName: String

    /** Whether this engine is available on the current device. */
    val isAvailable: StateFlow<Boolean>

    /** Whether this engine is currently listening. */
    val isListening: StateFlow<Boolean>

    /** Partial recognition result (updated in real-time during listening). */
    val partialText: StateFlow<String>

    /** Whether this engine requires a downloaded model. */
    val requiresModel: Boolean

    /** Whether the required model is present and loaded. */
    val isModelLoaded: StateFlow<Boolean>

    /**
     * Initializes the engine. Safe to call multiple times.
     * Returns true if the engine is ready to use.
     */
    fun init(context: Context): Boolean

    /**
     * Starts listening for speech. Partial results flow through [partialText].
     * [onResult] is called with the final recognized text.
     * [onError] is called with an error code on failure.
     */
    fun startListening(
        context: Context,
        language: String,
        onResult: (String) -> Unit,
        onError: (Int) -> Unit,
    )

    /** Stops listening and clears partial results. */
    fun stopListening()

    /** Releases all resources. */
    fun shutdown()
}

/** Error codes shared across engines. */
object SpeechError {
    const val NO_MATCH = 1
    const val NETWORK = 2
    const val NOT_AVAILABLE = 3
    const val MODEL_NOT_LOADED = 4
    const val AUDIO_CAPTURE = 5
    const val GENERIC = -1
}
