package com.newoether.agora.api

import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JNI bridge to the `agora_rs` native embedding functions.
 *
 * Replaces the pure-Kotlin [EmbeddingClient] for computing text embeddings
 * via OpenAI-compatible `/v1/embeddings` endpoints. All HTTP and JSON parsing
 * happen inside the Rust native library.
 */
object RustEmbeddingClient {
    init {
        NativeLib.load()
    }

    /**
     * Compute an embedding for a single text input.
     *
     * @param text    UTF-8 text to embed
     * @param apiKey  provider API key
     * @param model   embedding model ID (e.g. "text-embedding-3-small")
     * @param baseUrl provider base URL (e.g. "https://api.openai.com/v1")
     * @return JSON: `{"embedding": [0.1, 0.2, ...]}` or `{"error": "..."}`
     */
    external fun nativeComputeEmbedding(
        text: String,
        apiKey: String,
        model: String,
        baseUrl: String
    ): String

    /**
     * Compute embeddings for multiple text inputs in a single batch request.
     *
     * @param texts   JSON array of UTF-8 text strings
     * @param apiKey  provider API key
     * @param model   embedding model ID
     * @param baseUrl provider base URL
     * @return JSON: `{"embeddings": [[0.1, ...], [0.2, ...]]}` or `{"error": "..."}`
     */
    external fun nativeComputeEmbeddings(
        texts: String,
        apiKey: String,
        model: String,
        baseUrl: String
    ): String

    // ── Kotlin convenience wrappers ──────────────────────────────────

    @Serializable
    private data class SingleEmbeddingResult(
        val embedding: List<Float> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    private data class BatchEmbeddingResult(
        val embeddings: List<List<Float>> = emptyList(),
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Compute a single embedding vector.
     *
     * @return FloatArray or null on failure
     */
    fun computeEmbedding(
        text: String,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1"
    ): FloatArray? {
        return try {
            NativeLib.ensureLoaded()
            val raw = nativeComputeEmbedding(text, apiKey, model, baseUrl)
            val result = json.decodeFromString<SingleEmbeddingResult>(raw)
            if (result.error != null) {
                DebugLog.e(TAG, "Embedding error: ${result.error}")
                null
            } else {
                FloatArray(result.embedding.size) { i -> result.embedding[i] }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "computeEmbedding failed", e)
            null
        } catch (e: Throwable) {
            // 捕获 Error（UnsatisfiedLinkError 等）防止闪退
            DebugLog.e(TAG, "Native computeEmbedding error", e)
            null
        }
    }

    /**
     * Compute embeddings for a batch of texts.
     *
     * @return list of FloatArray (null entries for individual failures)
     */
    fun computeEmbeddings(
        texts: List<String>,
        apiKey: String,
        model: String = "text-embedding-3-small",
        baseUrl: String = "https://api.openai.com/v1"
    ): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        return try {
            NativeLib.ensureLoaded()
            val textsJson = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                texts
            )
            val raw = nativeComputeEmbeddings(textsJson, apiKey, model, baseUrl)
            val result = json.decodeFromString<BatchEmbeddingResult>(raw)
            if (result.error != null) {
                DebugLog.e(TAG, "Batch embedding error: ${result.error}")
                texts.map { null }
            } else {
                result.embeddings.map { vec ->
                    if (vec.isEmpty()) null
                    else FloatArray(vec.size) { i -> vec[i] }
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "computeEmbeddings failed", e)
            texts.map { null }
        } catch (e: Throwable) {
            // 捕获 Error（UnsatisfiedLinkError 等）防止闪退
            DebugLog.e(TAG, "Native computeEmbeddings error", e)
            texts.map { null }
        }
    }

    private const val TAG = "RustEmbeddingClient"
}
