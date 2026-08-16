package com.lxseek.chat.speech

import com.lxseek.chat.api.HttpClient
import com.lxseek.chat.util.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

private const val TAG = "RemoteTranscriber"

object RemoteTranscriber {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun transcribe(
        baseUrl: String,
        apiKey: String,
        audioFile: File,
        model: String,
        language: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            Log.w(TAG, "transcribe: apiKey or baseUrl blank, returning null")
            return@withContext null
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.w(TAG, "transcribe: audio file missing or empty: ${audioFile.absolutePath}")
            return@withContext null
        }
        val url = "${baseUrl.trimEnd('/')}/audio/transcriptions"
        Log.i(TAG, "transcribe: url=$url, model=$model, file=${audioFile.name} (${audioFile.length()} bytes)")
        val mimeType = when (audioFile.extension.lowercase()) {
            "wav" -> "audio/wav"
            "m4a", "mp4" -> "audio/mpeg"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody(mimeType.toMediaType()),
            )
        if (!language.isNullOrBlank()) {
            builder.addFormDataPart("language", language)
        }
        val request = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(builder.build())
            .build()
        try {
            HttpClient.client.newCall(request).execute().use { resp ->
                Log.i(TAG, "transcribe: HTTP ${resp.code} ${resp.message}")
                if (!resp.isSuccessful) {
                    val errBody = try { resp.body?.string()?.take(500) } catch (_: Throwable) { null }
                    Log.w(TAG, "transcribe: HTTP ${resp.code} failed: $errBody")
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                val parsed = json.parseToJsonElement(body).jsonObject
                val text = parsed["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                Log.i(TAG, "transcribe: parsed text='${text?.take(100)}'")
                text
            }
        } catch (e: Throwable) {
            Log.e(TAG, "transcribe failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }
}
