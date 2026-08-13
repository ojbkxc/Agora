package com.lxseek.chat.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
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
    private const val MAX_LOG = 300
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initialized = false
    @Volatile private var initGeneration = 0
    @Volatile private var enginesToTry: List<String> = emptyList()
    @Volatile private var currentEngineIndex = 0

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

    private fun log(level: String, msg: String) {
        val ts = logTimeFormat.format(Date())
        val entry = "$ts $level/$TAG: $msg"
        if (level == "E") Log.e(TAG, msg) else Log.d(TAG, msg)
        synchronized(logBuffer) {
            logBuffer.add(entry)
            if (logBuffer.size > MAX_LOG) logBuffer.removeAt(0)
        }
    }

    fun getLogText(): String {
        val sb = StringBuilder()
        sb.append("=== TTS Diagnostic Log ===\n")
        sb.append("Date: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("App: Agora v1.0.17\n")
        val info = getDiagnosticInfo()
        sb.append("Initialized: ${info.initialized}\n")
        sb.append("Available: ${info.available}\n")
        sb.append("Engine: ${info.engineName}\n")
        sb.append("Available engines: ${info.availableEngines}\n")
        sb.append("Lang missing data: ${info.langMissingData}\n")
        sb.append("Last init status: ${info.lastInitStatus}\n")
        sb.append("Last speak result: ${info.lastSpeakResult}\n")
        sb.append("Last language result: ${info.lastLanguageResult}\n")
        sb.append("=== Log Entries ===\n")
        synchronized(logBuffer) { for (e in logBuffer) sb.append(e).append('\n') }
        return sb.toString()
    }

    fun clearLog() { synchronized(logBuffer) { logBuffer.clear() } }

    fun init(context: Context) {
        if (tts != null && initialized) return
        if (tts != null && !initialized) {
            try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
            tts = null
        }
        val appCtx = context.applicationContext
        this.appContext = appCtx
        _lastInitStatus.value = "PENDING"
        val pm = appCtx.packageManager
        val ttsIntent = Intent("android.speech.tts.TTS_SERVICE")
        val resolvedEngines = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(ttsIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(ttsIntent, 0)
            }.map { it.serviceInfo.packageName }
        } catch (_: Throwable) { emptyList() }
        val defaultEngine = try {
            Settings.Secure.getString(appCtx.contentResolver, "tts_default_synth")
        } catch (_: Throwable) { null }
        enginesToTry = mutableListOf<String>().apply {
            if (!defaultEngine.isNullOrEmpty()) add(defaultEngine)
            for (e in resolvedEngines) if (e !in this) add(e)
            if ("com.google.android.tts" !in this) add("com.google.android.tts")
        }
        currentEngineIndex = 0
        log("D", "init: enginesToTry=$enginesToTry")
        tryNextEngine(appCtx)
    }

    private fun tryNextEngine(ctx: Context) {
        if (currentEngineIndex >= enginesToTry.size) {
            log("E", "All engines exhausted")
            _lastInitStatus.value = "FAILED:all_exhausted"
            initialized = false; _isAvailable.value = false
            return
        }
        val engine = enginesToTry[currentEngineIndex]
        val generation = ++initGeneration
        log("D", "Trying engine ${currentEngineIndex + 1}/${enginesToTry.size}: $engine")
        tts = try {
            TextToSpeech(ctx, { status -> onInitResult(generation, status, engine, ctx) }, engine)
        } catch (e: Throwable) {
            log("E", "Constructor exception for $engine: ${e.message}")
            currentEngineIndex++
            tryNextEngine(ctx)
            null
        }
    }

    fun reinit(context: Context) { shutdown(); init(context) }

    private fun onInitResult(generation: Int, status: Int, engine: String, ctx: Context) {
        if (generation != initGeneration) return
        if (status == TextToSpeech.SUCCESS) {
            initialized = true; _isAvailable.value = true
            _lastInitStatus.value = "SUCCESS:$engine"
            log("D", "init SUCCESS with engine=$engine, engines=${tts?.engines?.map { it.name }}")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { log("D", "onStart $utteranceId"); _isPlaying.value = true }
                override fun onDone(utteranceId: String?) { log("D", "onDone $utteranceId"); _isPlaying.value = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { log("D", "onError $utteranceId"); _isPlaying.value = false }
                override fun onError(utteranceId: String?, errorCode: Int) { log("E", "onError $utteranceId code=$errorCode"); _isPlaying.value = false }
            })
            pendingText?.let { text ->
                pendingText = null
                val lang = pendingLanguage; val rate = pendingRate
                log("D", "flushing pendingText on main thread")
                mainHandler.post { speakInternal(text, lang, rate) }
            }
        } else {
            log("E", "init FAILED for engine=$engine status=$status")
            try { tts?.shutdown() } catch (_: Throwable) {}
            tts = null
            currentEngineIndex++
            tryNextEngine(ctx)
        }
    }

    fun speak(text: String, language: String = "system", rate: Float = 1.0f): Boolean {
        if (text.isBlank()) { log("D", "speak: text is blank"); return false }
        if (!initialized || tts == null) {
            log("D", "speak: buffering (initialized=$initialized tts=${tts != null})")
            pendingText = text; pendingLanguage = language; pendingRate = rate
            return true
        }
        return speakInternal(text, language, rate)
    }

    private fun speakInternal(text: String, language: String, rate: Float): Boolean {
        val engine = tts ?: run { log("E", "speakInternal: engine is null"); _lastSpeakResult.value = "ERROR:no_engine"; return false }
        val locale = when (language) { "en" -> Locale.US; "zh" -> Locale.SIMPLIFIED_CHINESE; else -> Locale.getDefault() }
        val langResult = engine.setLanguage(locale)
        val langResultStr = langResultToString(langResult)
        log("D", "setLanguage($locale)=$langResultStr lang=$language")
        _lastLanguageResult.value = "$language:$langResultStr"
        _langMissingData.value = (langResult == TextToSpeech.LANG_MISSING_DATA)
        if (langResult == TextToSpeech.LANG_NOT_SUPPORTED || langResult == TextToSpeech.LANG_MISSING_DATA) {
            val fb = engine.setLanguage(Locale.getDefault())
            log("D", "fallback setLanguage(default)=${langResultToString(fb)}")
            if (fb == TextToSpeech.LANG_MISSING_DATA) _langMissingData.value = true
            if (fb == TextToSpeech.LANG_NOT_SUPPORTED || fb == TextToSpeech.LANG_MISSING_DATA) engine.setLanguage(Locale.US)
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        val speakStr = if (speakResult == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR:$speakResult"
        log("D", "speak result=$speakStr textLen=${text.length} text='${text.take(80)}'")
        _lastSpeakResult.value = speakStr
        if (speakResult != TextToSpeech.SUCCESS) { _isPlaying.value = false; return false }
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
        initGeneration++; tts?.stop(); tts?.shutdown(); tts = null
        initialized = false; _isAvailable.value = false; _isPlaying.value = false; _langMissingData.value = false
        _lastInitStatus.value = "IDLE"; _lastSpeakResult.value = ""; _lastLanguageResult.value = ""
        pendingText = null
    }

    fun getDiagnosticInfo(): TtsDiagnosticInfo {
        val engine = tts
        return TtsDiagnosticInfo(
            initialized = initialized, available = _isAvailable.value,
            engineName = engine?.defaultEngine,
            availableEngines = engine?.engines?.map { it.name } ?: emptyList(),
            langMissingData = _langMissingData.value,
            lastInitStatus = _lastInitStatus.value, lastSpeakResult = _lastSpeakResult.value, lastLanguageResult = _lastLanguageResult.value,
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
