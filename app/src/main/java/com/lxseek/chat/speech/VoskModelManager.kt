package com.lxseek.chat.speech

import android.content.Context
import com.lxseek.chat.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

private const val VOSK_MM_TAG = "VoskModelManager"

object VoskModelManager {

    data class LanguageModel(
        val code: String,
        val displayName: String,
        val modelName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val isFullSize: Boolean = false,
    )

    val AVAILABLE_MODELS: List<LanguageModel> = listOf(
        LanguageModel("en", "English (Small)", "vosk-model-small-en-us-0.15",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40_000_000L),
        LanguageModel("en-full", "English (Full)", "vosk-model-en-us-0.22-lgraph",
            "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip", 128_000_000L, true),
        LanguageModel("zh", "中文 (Small)", "vosk-model-small-cn-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", 42_000_000L),
        LanguageModel("zh-full", "中文 (Full)", "vosk-model-cn-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip", 1_300_000_000L, true),
        LanguageModel("ru", "Русский (Small)", "vosk-model-small-ru-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 45_000_000L),
        LanguageModel("de", "Deutsch (Small)", "vosk-model-small-de-0.15",
            "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 45_000_000L),
        LanguageModel("es", "Español (Small)", "vosk-model-small-es-0.42",
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 39_000_000L),
        LanguageModel("fr", "Français (Small)", "vosk-model-small-fr-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 41_000_000L),
        LanguageModel("it", "Italiano (Small)", "vosk-model-small-it-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 48_000_000L),
        LanguageModel("pt", "Português (Small)", "vosk-model-small-pt-0.3",
            "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip", 31_000_000L),
        LanguageModel("ja", "日本語 (Small)", "vosk-model-small-ja-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 48_000_000L),
        LanguageModel("ko", "한국어 (Small)", "vosk-model-small-ko-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip", 82_000_000L),
        LanguageModel("tr", "Türkçe (Small)", "vosk-model-small-tr-0.3",
            "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip", 35_000_000L),
        LanguageModel("vi", "Tiếng Việt (Small)", "vosk-model-small-vn-0.4",
            "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip", 32_000_000L),
        LanguageModel("nl", "Nederlands (Small)", "vosk-model-small-nl-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip", 39_000_000L),
        LanguageModel("pl", "Polski (Small)", "vosk-model-small-pl-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip", 50_000_000L),
        LanguageModel("hi", "हिन्दी (Small)", "vosk-model-small-hi-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 42_000_000L),
        LanguageModel("uk", "Українська (Nano)", "vosk-model-small-uk-v3-nano",
            "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip", 73_000_000L),
        LanguageModel("ca", "Català (Small)", "vosk-model-small-ca-0.4",
            "https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip", 42_000_000L),
        LanguageModel("fa", "فارسی (Small)", "vosk-model-small-fa-0.5",
            "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.5.zip", 47_000_000L),
        LanguageModel("kz", "Қазақша (Small)", "vosk-model-small-kz-0.15",
            "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.15.zip", 42_000_000L),
        LanguageModel("sv", "Svenska (Small)", "vosk-model-small-sv-rhasspy-0.15",
            "https://alphacephei.com/vosk/models/vosk-model-small-sv-rhasspy-0.15.zip", 35_000_000L),
        LanguageModel("cs", "Čeština (Small)", "vosk-model-small-cs-0.4-rhasspy",
            "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip", 44_000_000L),
        LanguageModel("el", "Ελληνικά (Small)", "vosk-model-el-gr-0.7",
            "https://alphacephei.com/vosk/models/vosk-model-el-gr-0.7.zip", 54_000_000L),
        LanguageModel("id", "Bahasa Indonesia", "vosk-model-small-id-0.22",
            "https://alphacephei.com/vosk/models/vosk-model-small-id-0.22.zip", 42_000_000L),
    )

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    fun getModelDir(context: Context, code: String): File =
        File(context.filesDir, "vosk/model-$code")

    fun isModelDownloaded(context: Context, code: String): Boolean {
        val dir = getModelDir(context, code)
        if (!dir.exists()) return false
        return File(dir, "am/final.mdl").exists() ||
            File(dir, "am/model.mdl").exists() ||
            dir.walkTopDown().any { it.isFile && it.name.endsWith(".mdl") }
    }

    fun getDownloadedModels(context: Context): List<String> =
        AVAILABLE_MODELS.filter { isModelDownloaded(context, it.code) }.map { it.code }

    fun getModelSize(context: Context, code: String): Long {
        val dir = getModelDir(context, code)
        return if (dir.exists()) dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() else 0L
    }

    fun deleteModel(context: Context, code: String): Boolean = try {
        val dir = getModelDir(context, code)
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(VOSK_MM_TAG, "Deleted model for $code")
            true
        } else false
    } catch (e: Throwable) {
        Log.e(VOSK_MM_TAG, "Failed to delete model $code: ${e.message}", e)
        false
    }

    suspend fun downloadModel(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        val langModel = AVAILABLE_MODELS.find { it.code == code }
            ?: run { Log.e(VOSK_MM_TAG, "Unknown language code: $code"); return@withContext false }
        if (isModelDownloaded(context, code)) {
            Log.i(VOSK_MM_TAG, "Model $code already downloaded")
            return@withContext true
        }
        try {
            val modelDir = getModelDir(context, code)
            val parentDir = modelDir.parentFile ?: context.filesDir
            parentDir.mkdirs()
            Log.i(VOSK_MM_TAG, "Downloading Vosk model $code (${langModel.sizeBytes / 1_000_000}MB) from ${langModel.downloadUrl}")

            val url = URL(langModel.downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 60_000
            conn.readTimeout = 300_000
            conn.setRequestProperty("User-Agent", "Agora/1.0")
            conn.connect()

            if (conn.responseCode !in 200..299) {
                Log.e(VOSK_MM_TAG, "HTTP ${conn.responseCode} downloading model $code")
                return@withContext false
            }

            val totalBytes = conn.contentLengthLong.let { if (it > 0) it else langModel.sizeBytes }
            val zipFile = File(parentDir, "${langModel.modelName}.zip")
            var downloaded = 0L

            conn.inputStream.use { input ->
                zipFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        val progress = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        _downloadProgress.value = _downloadProgress.value + (code to progress)
                    }
                }
            }

            _downloadProgress.value = _downloadProgress.value + (code to -1)
            Log.i(VOSK_MM_TAG, "Download complete (${downloaded / 1_000_000}MB), extracting...")

            extractZip(zipFile, parentDir)

            val extractedDir = File(parentDir, langModel.modelName)
            if (extractedDir.exists() && extractedDir != modelDir) {
                if (modelDir.exists()) modelDir.deleteRecursively()
                if (!extractedDir.renameTo(modelDir)) {
                    extractedDir.copyRecursively(modelDir, overwrite = true)
                    extractedDir.deleteRecursively()
                }
            }
            zipFile.delete()
            _downloadProgress.value = _downloadProgress.value - code

            val success = isModelDownloaded(context, code)
            if (success) Log.i(VOSK_MM_TAG, "Model $code ready at ${modelDir.absolutePath}")
            else Log.e(VOSK_MM_TAG, "Model $code extraction failed — no .mdl file found")
            success
        } catch (e: Throwable) {
            Log.e(VOSK_MM_TAG, "Download failed for $code: ${e.javaClass.simpleName}: ${e.message}", e)
            _downloadProgress.value = _downloadProgress.value - code
            false
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                try {
                    if (entry.isDirectory) outFile.mkdirs()
                    else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { fos ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) fos.write(buffer, 0, len)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(VOSK_MM_TAG, "Failed to extract ${entry.name}: ${e.message}", e)
                    throw e
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        Log.i(VOSK_MM_TAG, "Extraction complete")
    }

    fun getDiagnosticText(context: Context): String = buildString {
        appendLine("=== Vosk Model Manager ===")
        appendLine("Available models: ${AVAILABLE_MODELS.size}")
        appendLine("Downloaded models:")
        getDownloadedModels(context).forEach { code ->
            val size = getModelSize(context, code)
            appendLine("  $code: ${size / 1_000_000}MB")
        }
        val activeDownloads = _downloadProgress.value
        if (activeDownloads.isNotEmpty()) {
            appendLine("Active downloads:")
            activeDownloads.forEach { (code, progress) ->
                appendLine("  $code: ${if (progress == -1) "extracting..." else "$progress%"}")
            }
        }
    }
}
