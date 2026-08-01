package com.newoether.agora.api

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Rust-backed [LlmProvider] implementation for Ollama.
 *
 * Delegates all HTTP transport and JSONL parsing to the `agora_rs` native
 * library and bridges streaming events back into a Kotlin [Flow].
 */
open class RustOllamaProvider : LlmProvider {
    override val name: String = Constants.PROVIDER_OLLAMA
    override val defaultBaseUrl: String = ""

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = callbackFlow {
        val baseUrl = config.baseUrl?.trimEnd('/')?.ifBlank { null }
            ?: defaultBaseUrl.ifEmpty { null }
        if (baseUrl == null) {
            trySend(StreamEvent.Error(GenerationError.Configuration("Ollama base URL not configured")))
            close()
            return@callbackFlow
        }

        val handle = try {
            withContext(Dispatchers.IO) {
                val providerConfigJson = json.encodeToString(
                    RustProviderConfig(
                        apiKey = config.apiKey,
                        modelId = config.modelId,
                        systemPrompt = config.systemPrompt,
                        maxContextWindow = config.maxContextWindow,
                        thinkingEnabled = config.thinkingEnabled,
                        thinkingLevel = config.thinkingLevel,
                        baseUrl = baseUrl,
                        includeImages = config.includeImages,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        topP = config.topP,
                        frequencyPenalty = config.frequencyPenalty,
                        presencePenalty = config.presencePenalty
                    )
                )
                RustProvider.nativeCreateProvider("ollama", providerConfigJson)
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to create Ollama provider", e)
            trySend(StreamEvent.Error(GenerationError.Unknown(e)))
            close()
            return@callbackFlow
        }

        if (handle < 0) {
            trySend(StreamEvent.Error(GenerationError.Configuration("Rust Ollama provider creation failed (code $handle)")))
            close()
            return@callbackFlow
        }

        try {
            val messagesJson = withContext(Dispatchers.IO) {
                json.encodeToString(messages.map { it.toRustMessage() })
            }

            val callback = object : RustProvider.RustStreamCallback {
                override fun onEvent(eventJson: String) {
                    try {
                        val event = json.decodeFromString<RustStreamEvent>(eventJson)
                        val mapped = event.toStreamEvent()
                        if (mapped != null) {
                            trySend(mapped)
                        }
                    } catch (e: Exception) {
                        DebugLog.e(TAG, "Failed to parse stream event: $eventJson", e)
                    }
                }
            }

            withContext(Dispatchers.IO) {
                RustProvider.nativeGenerate(handle, messagesJson, "{}", callback)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Generation failed", e)
            trySend(StreamEvent.Error(GenerationError.Unknown(e)))
        } finally {
            withContext(Dispatchers.IO) {
                RustProvider.nativeDestroyProvider(handle)
            }
            close()
        }

        awaitClose {
            kotlinx.coroutines.runCatching {
                RustProvider.nativeDestroyProvider(handle)
            }
        }
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val effectiveBaseUrl = baseUrl?.trimEnd('/')?.ifBlank { null } ?: "http://localhost:11434"
                val providerConfigJson = json.encodeToString(
                    RustProviderConfig(
                        apiKey = apiKey,
                        modelId = "",
                        baseUrl = effectiveBaseUrl
                    )
                )
                val handle = RustProvider.nativeCreateProvider("ollama", providerConfigJson)
                if (handle < 0) return@withContext emptyList()

                try {
                    val result = RustProvider.nativeFetchModels(handle, apiKey, effectiveBaseUrl)
                    parseModelList(result)
                } finally {
                    RustProvider.nativeDestroyProvider(handle)
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "fetchModels failed", e)
                emptyList()
            }
        }

    private fun parseModelList(jsonStr: String): List<String> {
        return try {
            val response = json.decodeFromString<RustModelListResponse>(jsonStr)
            response.models.sorted()
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse model list: $jsonStr", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "RustOllamaProvider"
    }
}
