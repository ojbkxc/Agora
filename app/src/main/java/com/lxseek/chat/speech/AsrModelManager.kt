package com.lxseek.chat.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages ASR model downloads for sherpa-onnx.
 *
 * Models are downloaded from the sherpa-onnx GitHub Releases and stored in the app's
 * files directory. The manager tracks download progress and model presence.
 */
object AsrModelManager {

    private const val BASE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private const val MODEL_DIR = "asr-models"

    data class AsrModel(
        val id: String,
        val displayName: String,
        val url: String,
        val sizeMb: Int,
        val language: String,
        val type: String,
    )

    val availableModels = listOf(
        AsrModel(
            id = "zipformer-bilingual-zh-en",
            displayName = "Zipformer Bilingual zh+en (Streaming)",
            url = "$BASE_URL/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            sizeMb = 68,
            language = "zh+en",
            type = "streaming",
        ),
        AsrModel(
            id = "paraformer-zh",
            displayName = "Paraformer Chinese (Offline)",
            url = "$BASE_URL/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2",
            sizeMb = 41,
            language = "zh",
            type = "offline",
        ),
        AsrModel(
            id = "whisper-tiny",
            displayName = "Whisper Tiny (Multilingual)",
            url = "$BASE_URL/sherpa-onnx-whisper-tiny.tar.bz2",
            sizeMb = 39,
            language = "multi",
            type = "offline",
        ),
        AsrModel(
            id = "sensevoice-multi",
            displayName = "SenseVoice Multilingual (zh-en-ja-ko-yue)",
            url = "$BASE_URL/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            sizeMb = 224,
            language = "multi",
            type = "offline",
        ),
    )

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    fun modelDir(context: Context): File =
        File(context.filesDir, MODEL_DIR).apply { mkdirs() }

    fun isModelDownloaded(context: Context, modelId: String): Boolean =
        File(modelDir(context), modelId).exists()

    fun getDownloadedModels(context: Context): List<String> =
        modelDir(context).listFiles()?.map { it.name } ?: emptyList()

    suspend fun downloadModel(context: Context, model: AsrModel): Boolean = withContext(Dispatchers.IO) {
        if (_isDownloading.value) return@withContext false
        _isDownloading.value = true
        _downloadProgress.value = 0
        var connection: HttpURLConnection? = null
        try {
            val targetDir = File(modelDir(context), model.id)
            if (targetDir.exists()) {
                _downloadProgress.value = 100
                _activeModelId.value = model.id
                return@withContext true
            }
            targetDir.mkdirs()
            val url = URL(model.url)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.instanceFollowRedirects = true
            val totalBytes = connection.contentLength.toLong().coerceAtLeast(1)
            val targetFile = File(targetDir, "model.tar.bz2")
            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        _downloadProgress.value = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99)
                    }
                }
            }
            _downloadProgress.value = 100
            _activeModelId.value = model.id
            true
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
            _isDownloading.value = false
        }
    }

    fun deleteModel(context: Context, modelId: String): Boolean {
        _activeModelId.value = null
        return File(modelDir(context), modelId).deleteRecursively()
    }

    fun getActiveModelPath(context: Context): String? {
        val id = _activeModelId.value ?: return null
        val dir = File(modelDir(context), id)
        return if (dir.exists()) dir.absolutePath else null
    }
}
