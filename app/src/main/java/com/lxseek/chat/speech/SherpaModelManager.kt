package com.lxseek.chat.speech

import android.content.Context
import com.lxseek.chat.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads and manages sherpa-onnx model files on the device.
 *
 * Models are stored under `context.getExternalFilesDir("sherpa_models")/<subdir>/`.
 * Preset URLs point to GitHub Releases (VAD) and HuggingFace (ASR/TTS).
 */
object SherpaModelManager {

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    enum class ModelKind(val subdir: String, val displayName: String) {
        VAD("vad", "Silero VAD"),
        ASR_ZIPFORMER_BILINGUAL("asr_zipformer_bilingual_zh_en", "Zipformer Bilingual zh-en"),
        TTS_KOKORO("tts_kokoro", "Kokoro-82M"),
    }

    fun modelDir(context: Context, kind: ModelKind): File {
        val base = context.getExternalFilesDir("sherpa_models") ?: File(context.filesDir, "sherpa_models")
        return File(base, kind.subdir)
    }

    fun isModelPresent(context: Context, kind: ModelKind): Boolean {
        val dir = modelDir(context, kind)
        if (!dir.isDirectory) return false
        return when (kind) {
            ModelKind.VAD -> File(dir, "silero_vad.onnx").exists()
            ModelKind.ASR_ZIPFORMER_BILINGUAL -> listOf(
                "encoder-epoch-99-avg-1.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.onnx",
                "tokens.txt",
            ).all { File(dir, it).exists() }
            ModelKind.TTS_KOKORO -> listOf("model.onnx", "voices.bin", "tokens.txt").all { File(dir, it).exists() }
        }
    }

    /**
     * Downloads a single file from [url] to [target].
     * Reports progress (0..1) via [downloadProgress] under the key [progressKey].
     */
    private suspend fun downloadFile(url: String, target: File, progressKey: String): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(url).build()
            HttpClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                val total = body.contentLength()
                FileOutputStream(tmp).use { out ->
                    val source = body.source()
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val frac = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                            _downloadProgress.value = _downloadProgress.value + (progressKey to frac)
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext false
            }
            _downloadProgress.value = _downloadProgress.value + (progressKey to 1f)
            true
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    /**
     * Downloads the Silero VAD model (single file, ~2MB).
     */
    suspend fun downloadVad(context: Context): Boolean {
        if (_isDownloading.value) return false
        _isDownloading.value = true
        try {
            val dir = modelDir(context, ModelKind.VAD)
            val target = File(dir, "silero_vad.onnx")
            return downloadFile(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                target,
                "vad",
            )
        } finally {
            _isDownloading.value = false
        }
    }

    /**
     * Downloads the streaming zipformer bilingual zh-en ASR model (4 files).
     */
    suspend fun downloadAsrZipformerBilingual(context: Context): Boolean {
        if (_isDownloading.value) return false
        _isDownloading.value = true
        try {
            val dir = modelDir(context, ModelKind.ASR_ZIPFORMER_BILINGUAL)
            val base = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main"
            val files = listOf(
                "encoder-epoch-99-avg-1.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.onnx",
                "tokens.txt",
            )
            for (f in files) {
                if (!downloadFile("$base/$f", File(dir, f), "asr_$f")) return false
            }
            return true
        } finally {
            _isDownloading.value = false
        }
    }

    /**
     * Downloads the Kokoro-82M TTS model.
     * Note: Kokoro needs voices.bin + tokens.txt + espeak-ng-data dir; espeak-ng-data
     * is large (~100MB) and not auto-downloaded here. Users can manually provide it.
     */
    suspend fun downloadTtsKokoro(context: Context): Boolean {
        if (_isDownloading.value) return false
        _isDownloading.value = true
        try {
            val dir = modelDir(context, ModelKind.TTS_KOKORO)
            val base = "https://huggingface.co/k2-fsa/kokoro-82M/resolve/main"
            val files = listOf("model.onnx", "voices.bin", "tokens.txt")
            for (f in files) {
                if (!downloadFile("$base/$f", File(dir, f), "tts_$f")) return false
            }
            return true
        } finally {
            _isDownloading.value = false
        }
    }

    fun clearProgress(key: String) {
        _downloadProgress.value = _downloadProgress.value - key
    }

    fun deleteModel(context: Context, kind: ModelKind): Boolean {
        val dir = modelDir(context, kind)
        return dir.deleteRecursively()
    }
}
