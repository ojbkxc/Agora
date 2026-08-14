package com.lxseek.chat.speech

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class ModelDownloadState {
    NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, ACTIVE
}

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
        AsrModel("zipformer-bilingual-zh-en", "Zipformer Bilingual zh+en (Streaming)",
            "$BASE_URL/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            68, "zh+en", "streaming"),
        AsrModel("paraformer-zh", "Paraformer Chinese (Offline)",
            "$BASE_URL/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2",
            41, "zh", "offline"),
        AsrModel("whisper-tiny", "Whisper Tiny (Multilingual)",
            "$BASE_URL/sherpa-onnx-whisper-tiny.tar.bz2",
            39, "multi", "offline"),
        AsrModel("sensevoice-multi", "SenseVoice Multilingual (zh-en-ja-ko-yue)",
            "$BASE_URL/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            224, "multi", "offline"),
    )

    private val _modelStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val modelStates: StateFlow<Map<String, ModelDownloadState>> = _modelStates.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    fun modelDir(context: Context): File =
        File(context.filesDir, MODEL_DIR).apply { mkdirs() }

    fun isModelDownloaded(context: Context, modelId: String): Boolean {
        val dir = File(modelDir(context), modelId)
        return dir.exists() && (dir.listFiles()?.isNotEmpty() == true)
    }

    fun getDownloadedModels(context: Context): List<String> =
        modelDir(context).listFiles()?.filter { it.isDirectory && (it.listFiles()?.isNotEmpty() == true) }?.map { it.name } ?: emptyList()

    fun getModelState(context: Context, modelId: String): ModelDownloadState {
        _modelStates.value[modelId]?.let { if (it == ModelDownloadState.DOWNLOADING) return it }
        return if (isModelDownloaded(context, modelId)) {
            if (_activeModelId.value == modelId) ModelDownloadState.ACTIVE else ModelDownloadState.DOWNLOADED
        } else ModelDownloadState.NOT_DOWNLOADED
    }

    fun refreshStates(context: Context) {
        val states = availableModels.associate { it.id to getModelState(context, it.id) }
        _modelStates.value = states
    }

    fun activateModel(context: Context, modelId: String): Boolean {
        if (!isModelDownloaded(context, modelId)) return false
        _activeModelId.value = modelId
        refreshStates(context)
        SpeechRecognitionManager.sherpaEngine.loadModel(File(modelDir(context), modelId).absolutePath)
        return true
    }

    fun deactivateModel() {
        _activeModelId.value = null
    }

    fun downloadModel(context: Context, model: AsrModel, scope: CoroutineScope): Job {
        val existing = downloadJobs[model.id]
        if (existing?.isActive == true) return existing
        if (isModelDownloaded(context, model.id)) {
            updateState(model.id, ModelDownloadState.DOWNLOADED)
            return scope.launch { }
        }
        val job = scope.launch(Dispatchers.IO) {
            updateState(model.id, ModelDownloadState.DOWNLOADING)
            updateProgress(model.id, 0)
            var connection: HttpURLConnection? = null
            try {
                val targetDir = File(modelDir(context), model.id)
                targetDir.mkdirs()
                connection = URL(model.url).openConnection() as HttpURLConnection
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
                            if (!isActive) throw CancellationException()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            updateProgress(model.id, ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99))
                        }
                    }
                }
                updateProgress(model.id, 100)
                updateState(model.id, ModelDownloadState.DOWNLOADED)
            } catch (_: CancellationException) {
                File(modelDir(context), model.id).deleteRecursively()
                updateState(model.id, ModelDownloadState.NOT_DOWNLOADED)
                updateProgress(model.id, 0)
            } catch (_: Exception) {
                File(modelDir(context), model.id).deleteRecursively()
                updateState(model.id, ModelDownloadState.NOT_DOWNLOADED)
                updateProgress(model.id, 0)
            } finally {
                connection?.disconnect()
                downloadJobs.remove(model.id)
            }
        }
        downloadJobs[model.id] = job
        return job
    }

    fun cancelDownload(context: Context, modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        File(modelDir(context), modelId).deleteRecursively()
        updateState(modelId, ModelDownloadState.NOT_DOWNLOADED)
        updateProgress(modelId, 0)
    }

    fun deleteModel(context: Context, modelId: String): Boolean {
        if (_activeModelId.value == modelId) _activeModelId.value = null
        val deleted = File(modelDir(context), modelId).deleteRecursively()
        updateState(modelId, ModelDownloadState.NOT_DOWNLOADED)
        updateProgress(modelId, 0)
        return deleted
    }

    suspend fun importModel(context: Context, modelId: String, sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(modelDir(context), modelId)
        if (targetDir.exists()) return@withContext false
        targetDir.mkdirs()
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                File(targetDir, "model.tar.bz2").outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false
            updateState(modelId, ModelDownloadState.DOWNLOADED)
            true
        } catch (_: Exception) {
            targetDir.deleteRecursively()
            updateState(modelId, ModelDownloadState.NOT_DOWNLOADED)
            false
        }
    }

    fun getActiveModelPath(context: Context): String? {
        val id = _activeModelId.value ?: return null
        val dir = File(modelDir(context), id)
        return if (dir.exists()) dir.absolutePath else null
    }

    private fun updateState(modelId: String, state: ModelDownloadState) {
        _modelStates.value = _modelStates.value + (modelId to state)
    }

    private fun updateProgress(modelId: String, progress: Int) {
        _downloadProgress.value = _downloadProgress.value + (modelId to progress)
    }
}
