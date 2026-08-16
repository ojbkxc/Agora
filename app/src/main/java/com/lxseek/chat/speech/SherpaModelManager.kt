package com.lxseek.chat.speech

import android.content.Context
import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "SherpaModelMgr"

/**
 * Downloads and manages sherpa-onnx model files on the device.
 *
 * Models are stored under `context.getExternalFilesDir("sherpa_models")/<subdir>/`.
 * VAD/ASR use per-file downloads; TTS uses a tar.bz2 archive (contains espeak-ng-data
 * directory tree with 200+ files) extracted via Apache Commons Compress.
 *
 * URLs follow sherpa-onnx official release layout (k2-fsa/sherpa-onnx GitHub Releases).
 */
object SherpaModelManager {

    private const val GITHUB_ASR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private const val HF_ASR = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main"
    private const val HF_SENSE_VOICE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
    private const val GITHUB_TTS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    enum class Category { VAD, ASR, TTS }

    enum class ModelKind(val subdir: String, val displayName: String, val sizeHint: String, val category: Category, val description: String) {
        VAD("vad", "Silero VAD", "~2 MB", Category.VAD, "Voice activity detection"),
        ASR_ZIPFORMER_BILINGUAL("asr_zipformer_bilingual_zh_en", "Zipformer Bilingual zh-en (int8)", "~180 MB", Category.ASR, "Streaming, zh+en, real-time partial"),
        ASR_SENSE_VOICE("asr_sense_voice", "SenseVoice Multi-lang (int8)", "~100 MB", Category.ASR, "Offline, zh+en+ja+ko+yue, higher accuracy"),
        TTS_KOKORO("tts_kokoro_v1_0", "Kokoro Multi-lang v1.0", "~200 MB", Category.TTS, "zh+en, natural voice"),
        TTS_KOKORO_V1_1("tts_kokoro_v1_1", "Kokoro Multi-lang v1.1", "~200 MB", Category.TTS, "zh+en, improved quality"),
        TTS_KOKORO_INT8_V1_1("tts_kokoro_int8_v1_1", "Kokoro int8 v1.1", "~100 MB", Category.TTS, "zh+en, smaller size"),
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
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt",
            ).all { File(dir, it).exists() }
            ModelKind.ASR_SENSE_VOICE -> listOf(
                "model.int8.onnx",
                "tokens.txt",
            ).all { File(dir, it).exists() }
            ModelKind.TTS_KOKORO, ModelKind.TTS_KOKORO_V1_1 -> kokoroFilesPresent(dir, "model.onnx")
            ModelKind.TTS_KOKORO_INT8_V1_1 -> kokoroFilesPresent(dir, "model.int8.onnx")
        }
    }

    private fun kokoroFilesPresent(dir: File, modelFile: String): Boolean =
        listOf(modelFile, "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-zh.txt",
            "phone-zh.fst", "date-zh.fst", "number-zh.fst").all { File(dir, it).exists() }
            && File(dir, "espeak-ng-data").isDirectory

    private suspend fun downloadFile(url: String, target: File, progressKey: String): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        Log.i(TAG, "downloadFile: $url -> ${target.name}")
        try {
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(url).build()
            HttpClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "downloadFile: HTTP ${resp.code} for $url")
                    return@withContext false
                }
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
            Log.i(TAG, "downloadFile: SUCCESS ${target.name} (${target.length()} bytes)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "downloadFile: FAILED ${target.name}: ${e.javaClass.simpleName}: ${e.message}")
            tmp.delete()
            false
        }
    }

    /** Extracts a .tar.bz2 archive to [destDir], stripping [stripComponents] leading path components. */
    private fun extractTarBz2(tarBz2: File, destDir: File, stripComponents: Int) {
        destDir.mkdirs()
        FileInputStream(tarBz2).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
                        var entry = tis.nextTarEntry
                        while (entry != null) {
                            val name = entry.name
                            if (!name.contains("..")) {
                                val parts = name.split("/")
                                val rel = if (parts.size > stripComponents) parts.drop(stripComponents).joinToString("/") else ""
                                if (rel.isNotEmpty()) {
                                    val out = File(destDir, rel)
                                    if (entry.isDirectory) {
                                        out.mkdirs()
                                    } else {
                                        out.parentFile?.mkdirs()
                                        FileOutputStream(out).use { o -> tis.copyTo(o) }
                                    }
                                }
                            }
                            entry = tis.nextTarEntry
                        }
                    }
                }
            }
        }
    }

    /** Downloads the Silero VAD model (single file, ~2MB). */
    suspend fun downloadVad(context: Context): Boolean {
        if (_isDownloading.value) return false
        _isDownloading.value = true
        try {
            val dir = modelDir(context, ModelKind.VAD)
            return downloadFile("$GITHUB_ASR/silero_vad.onnx", File(dir, "silero_vad.onnx"), "vad")
        } finally {
            _isDownloading.value = false
        }
    }

    /**
     * Downloads the streaming zipformer bilingual zh-en ASR model (int8 encoder/joiner
     * + float32 decoder, ~180MB total). Uses per-file download from HuggingFace mirror.
     */
    suspend fun downloadAsrZipformerBilingual(context: Context): Boolean {
        if (_isDownloading.value) return false
        _isDownloading.value = true
        try {
            val dir = modelDir(context, ModelKind.ASR_ZIPFORMER_BILINGUAL)
            val files = listOf(
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt",
            )
            for (f in files) {
                if (!downloadFile("$HF_ASR/$f", File(dir, f), "asr_$f")) return false
            }
            return true
        } finally {
            _isDownloading.value = false
        }
    }

    /**
     * Downloads the Kokoro multi-lang v1.0 TTS model as a tar.bz2 archive and extracts
     * it. The archive contains model.onnx, voices.bin, tokens.txt, espeak-ng-data/ dir,
     * lexicon files, and rule FSTs (~200MB). Strip 1 leading dir component.
     */
    suspend fun downloadTtsKokoro(context: Context): Boolean {
        if (_isDownloading.value) return false
        _isDownloading.value = true
        return try {
            val dir = modelDir(context, ModelKind.TTS_KOKORO)
            dir.deleteRecursively()
            val tarball = File(context.cacheDir, "kokoro-multi-lang-v1_0.tar.bz2")
            if (!downloadFile(
                    "$GITHUB_TTS/kokoro-multi-lang-v1_0.tar.bz2",
                    tarball, "tts_tar",
                )) return false
            withContext(Dispatchers.IO) {
                extractTarBz2(tarball, dir, stripComponents = 1)
            }
            tarball.delete()
            _downloadProgress.value = _downloadProgress.value + ("tts_tar" to 1f)
            isModelPresent(context, ModelKind.TTS_KOKORO)
        } catch (_: Throwable) {
            false
        } finally {
            _isDownloading.value = false
        }
    }

    /** Downloads SenseVoice multi-language ASR model (int8, ~100MB). */
    suspend fun downloadAsrSenseVoice(context: Context): Boolean {
        if (_isDownloading.value) return false
        _isDownloading.value = true
        try {
            val dir = modelDir(context, ModelKind.ASR_SENSE_VOICE)
            val files = listOf("model.int8.onnx", "tokens.txt")
            for (f in files) {
                if (!downloadFile("$HF_SENSE_VOICE/$f", File(dir, f), "asr_sv_$f")) return false
            }
            return true
        } finally {
            _isDownloading.value = false
        }
    }

    /** Downloads Kokoro v1.1 TTS model (~200MB). */
    suspend fun downloadTtsKokoroV11(context: Context): Boolean =
        downloadTtsTarball(context, ModelKind.TTS_KOKORO_V1_1, "kokoro-multi-lang-v1_1.tar.bz2")

    /** Downloads Kokoro int8 v1.1 TTS model (~100MB). */
    suspend fun downloadTtsKokoroInt8V11(context: Context): Boolean =
        downloadTtsTarball(context, ModelKind.TTS_KOKORO_INT8_V1_1, "kokoro-int8-multi-lang-v1_1.tar.bz2")

    private suspend fun downloadTtsTarball(context: Context, kind: ModelKind, tarballName: String): Boolean {
        if (_isDownloading.value) return false
        _isDownloading.value = true
        return try {
            val dir = modelDir(context, kind)
            dir.deleteRecursively()
            val tarball = File(context.cacheDir, tarballName)
            if (!downloadFile("$GITHUB_TTS/$tarballName", tarball, "tts_tar")) return false
            withContext(Dispatchers.IO) { extractTarBz2(tarball, dir, stripComponents = 1) }
            tarball.delete()
            _downloadProgress.value = _downloadProgress.value + ("tts_tar" to 1f)
            isModelPresent(context, kind)
        } catch (_: Throwable) {
            false
        } finally {
            _isDownloading.value = false
        }
    }

    /** Generic download dispatch by ModelKind. */
    suspend fun download(context: Context, kind: ModelKind): Boolean = when (kind) {
        ModelKind.VAD -> downloadVad(context)
        ModelKind.ASR_ZIPFORMER_BILINGUAL -> downloadAsrZipformerBilingual(context)
        ModelKind.ASR_SENSE_VOICE -> downloadAsrSenseVoice(context)
        ModelKind.TTS_KOKORO -> downloadTtsKokoro(context)
        ModelKind.TTS_KOKORO_V1_1 -> downloadTtsKokoroV11(context)
        ModelKind.TTS_KOKORO_INT8_V1_1 -> downloadTtsKokoroInt8V11(context)
    }

    fun clearProgress(key: String) {
        _downloadProgress.value = _downloadProgress.value - key
    }

    fun deleteModel(context: Context, kind: ModelKind): Boolean {
        val dir = modelDir(context, kind)
        return dir.deleteRecursively()
    }
}
