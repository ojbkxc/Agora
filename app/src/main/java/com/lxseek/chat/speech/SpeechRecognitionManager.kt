package com.lxseek.chat.speech

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates ASR engine selection at runtime.
 *
 * Selection priority:
 * 1. If sherpa-onnx is available and model is loaded → use sherpa (offline, privacy-preserving)
 * 2. If vosk is available and model is loaded → use vosk (offline, lightweight alternative)
 * 3. If system SpeechRecognizer is available → use system (online/cloud fallback)
 * 4. Otherwise → no engine available
 *
 * The user can force a specific engine via settings (ASR_ENGINE_PREF).
 */
object SpeechRecognitionManager {

    val systemEngine = SystemSpeechEngine()
    val sherpaEngine = SherpaAsrEngine()
    val voskEngine = VoskAsrEngine()

    private val _activeEngine = MutableStateFlow<SpeechEngine?>(null)
    val activeEngine: StateFlow<SpeechEngine?> = _activeEngine.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    /** User preference: "auto", "system", "sherpa-onnx", "vosk". */
    @Volatile var preferredEngine: String = "auto"

    /**
     * Initializes available engines and selects the best one.
     * Returns the selected engine, or null if none available.
     */
    fun init(context: Context): SpeechEngine? {
        systemEngine.init(context)
        sherpaEngine.init(context)
        voskEngine.init(context)
        return selectEngine()
    }

    private fun selectEngine(): SpeechEngine? {
        val engine = when (preferredEngine) {
            "sherpa-onnx" -> {
                if (sherpaEngine.isAvailable.value && sherpaEngine.isModelLoaded.value) sherpaEngine
                else if (voskEngine.isAvailable.value && voskEngine.isModelLoaded.value) voskEngine
                else systemEngine.takeIf { it.isAvailable.value }
            }
            "vosk" -> {
                if (voskEngine.isAvailable.value && voskEngine.isModelLoaded.value) voskEngine
                else if (sherpaEngine.isAvailable.value && sherpaEngine.isModelLoaded.value) sherpaEngine
                else systemEngine.takeIf { it.isAvailable.value }
            }
            "system" -> systemEngine.takeIf { it.isAvailable.value }
            else -> {
                // auto: prefer sherpa (offline) → vosk (offline) → system
                if (sherpaEngine.isAvailable.value && sherpaEngine.isModelLoaded.value) sherpaEngine
                else if (voskEngine.isAvailable.value && voskEngine.isModelLoaded.value) voskEngine
                else systemEngine.takeIf { it.isAvailable.value }
            }
        }
        _activeEngine.value = engine
        _isAvailable.value = engine != null
        return engine
    }

    fun startListening(
        context: Context,
        language: String = "system",
        onResult: (String) -> Unit,
        onError: (Int) -> Unit,
    ) {
        val engine = _activeEngine.value ?: init(context)
        if (engine == null) {
            onError(SpeechError.NOT_AVAILABLE)
            return
        }
        engine.startListening(context, language, onResult, onError)
        _isListening.value = true
    }

    fun stopListening() {
        _activeEngine.value?.stopListening()
        _isListening.value = false
        _partialText.value = ""
    }

    fun shutdown() {
        systemEngine.shutdown()
        sherpaEngine.shutdown()
        voskEngine.shutdown()
        _activeEngine.value = null
        _isAvailable.value = false
        _isListening.value = false
        _partialText.value = ""
    }

    /** Returns a list of all engines with their availability status, for settings UI. */
    fun engineStatus(): List<EngineStatus> = listOf(
        EngineStatus(sherpaEngine.id, sherpaEngine.displayName, sherpaEngine.isAvailable.value, sherpaEngine.isModelLoaded.value),
        EngineStatus(voskEngine.id, voskEngine.displayName, voskEngine.isAvailable.value, voskEngine.isModelLoaded.value),
        EngineStatus(systemEngine.id, systemEngine.displayName, systemEngine.isAvailable.value, systemEngine.isModelLoaded.value),
    )

    data class EngineStatus(
        val id: String,
        val displayName: String,
        val available: Boolean,
        val modelLoaded: Boolean,
    )
}
