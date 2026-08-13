package com.lxseek.chat.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

data class TtsDiagnosticInfo(
    val initialized: Boolean,
    val available: Boolean,
    val engineName: String?,
    val availableEngines: List<String>,
    val langMissingData: Boolean,
    val lastInitStatus: String,
    val lastSpeakResult: String,
    val lastLanguageResult: String,
)

object TtsManager {
    private const val TAG = "TtsManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initialized = false
    @Volatile private var initGeneration = 0
    @Volatile private var initFailedCount = 0

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _langMissingData = MutableStateFlow(false)
    val langMissingData: StateFlow<Boolean> = _langMissingData.asStateFlow()

    private val _lastInitStatus = MutableStateFlow("IDLE")
    val lastInitStatus: StateFlow<String> = _lastInitStatus.asStateFlow()

    private val _lastSpeakResult = MutableStateFlow("")
    val lastSpeakResult: StateFlow<String> = _lastSpeakResult.asStateFlow()

    private val _lastLanguageResult = MutableStateFlow("")
    val lastLanguageResult: StateFlow<String> = _lastLanguageResult.asStateFlow()

    @Volatile private var pendingText: String? = null
    @Volatile private var pendingLanguage: String = "system"
    @Volatile private var pendingRate: Float = 1.0f
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (tts != null && initialized) return
        if (tts != null && !initialized) {
            try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
            tts = null
        }
        val generation = ++initGeneration
        val appCtx = context.applicationContext
        this.appContext = appCtx
        _lastInitStatus.value = "PENDING"
        val defaultEngine = try {
            Settings.Secure.getString(appCtx.contentResolver, "tts_default_synth")
        } catch (_: Throwable) { null }
        Log.d(TAG, "init: defaultEngine=$defaultEngine failedCount=$initFailedCount")
        val callback = { status: Int -> onInitResult(generation, status) }
        tts = try {
            if (defaultEngine != null && defaultEngine.isNotEmpty()) {
                TextToSpeech(appCtx, callback, defaultEngine)
            } else {
                TextToSpeech(appCtx, callback)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "TextToSpeech constructor exception", e)
            try { TextToSpeech(appCtx, callback) } catch (_: Throwable) { null }
        }
    }

    fun reinit(context: Context) {
        shutdown()
        init(context)
    }

    private fun onInitResult(generation: Int, status: Int) {
        if (generation != initGeneration) return
        if (status == TextToSpeech.SUCCESS) {
            initialized = true
            _isAvailable.value = true
            _lastInitStatus.value = "SUCCESS"
            initFailedCount = 0
            val engine = tts
            val engineName = engine?.defaultEngine
            val engines = engine?.engines?.map { it.name }
            Log.d(TAG, "init SUCCESS, engine=$engineName, engines=$engines")
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { Log.d(TAG, "onStart $utteranceId"); _isPlaying.value = true }
                override fun onDone(utteranceId: String?) { Log.d(TAG, "onDone $utteranceId"); _isPlaying.value = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { Log.d(TAG, "onError $utteranceId"); _isPlaying.value = false }
                override fun onError(utteranceId: String?, errorCode: Int) { Log.d(TAG, "onError $utteranceId code=$errorCode"); _isPlaying.value = false }
            })
            pendingText?.let { text ->
                pendingText = null
                val lang = pendingLanguage
                val rate = pendingRate
                Log.d(TAG, "flushing pendingText on main thread")
                mainHandler.post { speakInternal(text, lang, rate) }
            }
        } else {
            Log.e(TAG, "init FAILED status=$status")
            _lastInitStatus.value = "FAILED:$status"
            initialized = false
            _isAvailable.value = false
            _isPlaying.value = false
            initFailedCount++
            pendingText = null
            if (initFailedCount <= 2 && appContext != null) {
                Log.d(TAG, "retrying init with 2-arg constructor (fallback)")
                val generation2 = ++initGeneration
                val ctx = appContext!!
                tts = try { TextToSpeech(ctx) { s -> onInitResult(generation2, s) } } catch (_: Throwable) { null }
            }
        }
    }

    fun speak(text: String, language: String = "system", rate: Float = 1.0f): Boolean {
        if (text.isBlank()) { Log.d(TAG, "speak: text is blank"); return false }
        if (!initialized || tts == null) {
            Log.d(TAG, "speak: buffering (initialized=$initialized tts=${tts != null})")
            pendingText = text
            pendingLanguage = language
            pendingRate = rate
            return true
        }
        return speakInternal(text, language, rate)
    }

    private fun speakInternal(text: String, language: String, rate: Float): Boolean {
        val engine = tts ?: run {
            Log.e(TAG, "speakInternal: engine is null")
            _lastSpeakResult.value = "ERROR:no_engine"
            return false
        }
        val locale = when (language) {
            "en" -> Locale.US
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.getDefault()
        }
        val langResult = engine.setLanguage(locale)
        val langResultStr = langResultToString(langResult)
        Log.d(TAG, "setLanguage($locale)=$langResultStr lang=$language")
        _lastLanguageResult.value = "$language:$langResultStr"
        _langMissingData.value = (langResult == TextToSpeech.LANG_MISSING_DATA)
        if (langResult == TextToSpeech.LANG_NOT_SUPPORTED ||
            langResult == TextToSpeech.LANG_MISSING_DATA
        ) {
            val fb = engine.setLanguage(Locale.getDefault())
            Log.d(TAG, "fallback setLanguage(default)=${langResultToString(fb)}")
            if (fb == TextToSpeech.LANG_MISSING_DATA) _langMissingData.value = true
            if (fb == TextToSpeech.LANG_NOT_SUPPORTED || fb == TextToSpeech.LANG_MISSING_DATA) {
                engine.setLanguage(Locale.US)
            }
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        val speakStr = if (speakResult == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR:$speakResult"
        Log.d(TAG, "speak result=$speakStr textLen=${text.length} text='${text.take(80)}'")
        _lastSpeakResult.value = speakStr
        if (speakResult != TextToSpeech.SUCCESS) {
            _isPlaying.value = false
            return false
        }
        return true
    }

    private fun langResultToString(result: Int): String = when (result) {
        TextToSpeech.LANG_AVAILABLE -> "AVAILABLE"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "COUNTRY_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "COUNTRY_VAR_AVAILABLE"
        TextToSpeech.LANG_NOT_SUPPORTED -> "NOT_SUPPORTED"
        TextToSpeech.LANG_MISSING_DATA -> "MISSING_DATA"
        else -> "UNKNOWN:$result"
    }

    fun stop() { tts?.stop(); _isPlaying.value = false }

    fun shutdown() {
        initGeneration++
        tts?.stop(); tts?.shutdown(); tts = null
        initialized = false
        _isAvailable.value = false; _isPlaying.value = false; _langMissingData.value = false
        _lastInitStatus.value = "IDLE"; _lastSpeakResult.value = ""; _lastLanguageResult.value = ""
        pendingText = null
    }

    fun getDiagnosticInfo(): TtsDiagnosticInfo {
        val engine = tts
        return TtsDiagnosticInfo(
            initialized = initialized,
            available = _isAvailable.value,
            engineName = engine?.defaultEngine,
            availableEngines = engine?.engines?.map { it.name } ?: emptyList(),
            langMissingData = _langMissingData.value,
            lastInitStatus = _lastInitStatus.value,
            lastSpeakResult = _lastSpeakResult.value,
            lastLanguageResult = _lastLanguageResult.value,
        )
    }

    fun testSpeak(): Boolean = speak("Hello, this is a TTS test. 你好，这是语音测试。", "system", 1.0f)

    fun systemTtsSettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun installTtsDataIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)

    fun stripMarkdown(text: String): String =
        text
            .replace(Regex("`{1,3}[^`]*`{1,3}"), "")
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("#+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("[*_~>|]"), "")
            .replace(Regex("^[-*+]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
