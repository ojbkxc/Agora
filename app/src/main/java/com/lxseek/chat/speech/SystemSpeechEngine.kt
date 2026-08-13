package com.lxseek.chat.speech

import android.content.Context
import com.lxseek.chat.util.SttManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapter that wraps the existing [SttManager] (Android system SpeechRecognizer)
 * as a [SpeechEngine]. This is the always-available fallback engine.
 */
class SystemSpeechEngine : SpeechEngine {
    override val id: String = "system"
    override val displayName: String = "System Speech Recognition"
    override val isAvailable: StateFlow<Boolean> = SttManager.isAvailable
    override val isListening: StateFlow<Boolean> = SttManager.isListening
    override val partialText: StateFlow<String> = SttManager.partialText
    override val requiresModel: Boolean = false
    override val isModelLoaded: StateFlow<Boolean> = MutableStateFlow(true)

    override fun init(context: Context): Boolean {
        SttManager.init(context)
        return SttManager.isSupported(context)
    }

    override fun startListening(
        context: Context,
        language: String,
        onResult: (String) -> Unit,
        onError: (Int) -> Unit,
    ) {
        SttManager.startListening(context, language, onResult, onError)
    }

    override fun stopListening() = SttManager.stopListening()
    override fun shutdown() = SttManager.shutdown()
}
