package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
private data class EmbeddingRequest(
    val input: String,
    val model: String
)

@Serializable
private data class BatchEmbeddingRequest(
    val input: List<String>,
    val model: String
)

object EmbeddingClient {

    private val json = Json { ignoreUnknownKeys = true }

    fun computeEmbedding(
        text: String,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1"
    ): FloatArray? {
        return try {
            val url = "$baseUrl/embeddings"
            val body = json.encodeToString(EmbeddingRequest(input = text, model = model))
            val headers = buildMap {
                put("Content-Type", "application/json")
                if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
            }
            val response = HttpClient.post(url, body, headers) ?: return null
            val parsed = json.parseToJsonElement(response).jsonObject
            val data = parsed["data"]?.jsonArray ?: return null
            val embedding = data.firstOrNull()?.jsonObject?.get("embedding")?.jsonArray ?: return null
            FloatArray(embedding.size) { i -> embedding[i].jsonPrimitive.float }
        } catch (e: Exception) {
            DebugLog.e("EmbeddingClient", "computeEmbedding failed", e)
            null
        }
    }

    fun computeEmbeddings(
        texts: List<String>,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1"
    ): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        // Transient failures (429 / 5xx / network blips) used to surface as a
        // silent null batch, which marked every message in the page as "failed"
        // and forced the user into a manual re-cache. Retry with exponential
        // backoff so a brief rate-limit doesn't poison a whole embedding page.
        val maxAttempts = 3
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val url = "$baseUrl/embeddings"
                val body = json.encodeToString(BatchEmbeddingRequest(input = texts, model = model))
                val headers = buildMap {
                    put("Content-Type", "application/json")
                    if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
                }
                val response = HttpClient.post(url, body, headers)
                if (response == null) {
                    lastError = IllegalStateException("Embedding request returned no response")
                    backoff(attempt)
                    return@repeat
                }
                val parsed = json.parseToJsonElement(response).jsonObject
                val data = parsed["data"]?.jsonArray
                if (data == null) {
                    lastError = IllegalStateException("Embedding response missing 'data' field")
                    backoff(attempt)
                    return@repeat
                }
                return data.map { item ->
                    val embedding = item.jsonObject["embedding"]?.jsonArray ?: return@map null
                    FloatArray(embedding.size) { i -> embedding[i].jsonPrimitive.float }
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxAttempts - 1) backoff(attempt)
            }
        }
        DebugLog.e("EmbeddingClient", "computeEmbeddings failed after $maxAttempts attempts", lastError)
        return texts.map { null }
    }

    private fun backoff(attempt: Int) {
        // attempt is 0-based; 200ms, 400ms, 800ms … with a small jitter to avoid
        // synchronized retry storms when a batch page races many parallel calls.
        val base = 200L * (1L shl attempt)
        val jitter = (Math.random() * 80L).toLong()
        try {
            Thread.sleep(base + jitter)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
