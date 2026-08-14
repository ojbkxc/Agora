package com.lxseek.chat.speech

import com.lxseek.chat.api.HttpClient
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

object RemoteTranscriber {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun transcribe(
        baseUrl: String,
        apiKey: String,
        audioFile: File,
        model: String,
        language: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || baseUrl.isBlank()) return@withContext null
        val url = "${baseUrl.trimEnd('/')}/audio/transcriptions"
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "file",
                audioFile.name,
                audioFile.asRequestBody("audio/mpeg".toMediaType()),
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
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val parsed = json.parseToJsonElement(body).jsonObject
                parsed["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
