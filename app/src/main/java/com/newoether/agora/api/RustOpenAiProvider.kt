package com.newoether.agora.api

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Rust-backed [LlmProvider] implementation for OpenAI-compatible endpoints.
 *
 * All HTTP work and SSE parsing happen inside the `agora_rs` native library;
 * this class marshals Kotlin data classes to/from JSON and bridges the
 * streaming callback into a Kotlin [Flow].
 */
open class RustOpenAiProvider(
    /** 默认 base URL，内置供应商可覆盖为各自的 API 地址。 */
    override val defaultBaseUrl: String = "https://api.openai.com/v1",
) : LlmProvider {
    override val name: String = Constants.PROVIDER_OPENAI

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override fun generateResponse(
        messages: List<ChatMessage>,
        config: ProviderConfig
    ): Flow<StreamEvent> = callbackFlow {
        val handle = try {
            withContext(Dispatchers.IO) {
                NativeLib.ensureLoaded()
                val providerConfigJson = json.encodeToString(
                    RustProviderConfig(
                        apiKey = config.apiKey,
                        modelId = config.modelId,
                        systemPrompt = config.systemPrompt,
                        maxContextWindow = config.maxContextWindow,
                        codeExecutionEnabled = config.codeExecutionEnabled,
                        googleSearchEnabled = config.googleSearchEnabled,
                        thinkingEnabled = config.thinkingEnabled,
                        thinkingLevel = config.thinkingLevel,
                        thinkingBudgetEnabled = config.thinkingBudgetEnabled,
                        thinkingBudgetTokens = config.thinkingBudgetTokens,
                        baseUrl = config.baseUrl,
                        includeImages = config.includeImages,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens,
                        topP = config.topP,
                        frequencyPenalty = config.frequencyPenalty,
                        presencePenalty = config.presencePenalty,
                        userPrepend = config.userPrepend,
                        userPostpend = config.userPostpend,
                        tools = config.tools
                    )
                )
                RustProvider.nativeCreateProvider("openai", providerConfigJson)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to create OpenAI provider", e)
            trySend(StreamEvent.Error(GenerationError.Unknown(e)))
            close()
            return@callbackFlow
        } catch (e: Throwable) {
            // 捕获 Error（UnsatisfiedLinkError 等）防止闪退
            DebugLog.e(TAG, "Native error creating OpenAI provider", e)
            trySend(StreamEvent.Error(GenerationError.Unknown(RuntimeException(e))))
            close()
            return@callbackFlow
        }

        if (handle < 0) {
            trySend(StreamEvent.Error(GenerationError.Configuration("Rust provider creation failed (code $handle)")))
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
                    } catch (e: Throwable) {
                        // 捕获 Error（UnsatisfiedLinkError 等）防止闪退
                        DebugLog.e(TAG, "Native error parsing stream event: $eventJson", e)
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
        } catch (e: Throwable) {
            // 捕获逃逸的 Error（UnsatisfiedLinkError 等）防止闪退
            DebugLog.e(TAG, "Native generation error", e)
            trySend(StreamEvent.Error(GenerationError.Unknown(RuntimeException(e))))
        } finally {
            withContext(Dispatchers.IO) {
                RustProvider.destroyProvider(handle)
            }
            close()
        }

        awaitClose {
            // Ensure native handle is destroyed if the flow is cancelled upstream
            runCatching {
                RustProvider.destroyProvider(handle)
            }
        }
    }

    override suspend fun fetchModels(apiKey: String, baseUrl: String?): List<String> =
        withContext(Dispatchers.IO) {
            try {
                NativeLib.ensureLoaded()
                val effectiveBaseUrl = baseUrl ?: defaultBaseUrl
                val providerConfigJson = json.encodeToString(
                    RustProviderConfig(
                        apiKey = apiKey,
                        modelId = "",
                        baseUrl = effectiveBaseUrl
                    )
                )
                val handle = RustProvider.nativeCreateProvider("openai", providerConfigJson)
                if (handle < 0) throw IOException("Failed to create native provider (error code: $handle)")

                try {
                    val result = RustProvider.nativeFetchModels(handle, apiKey, effectiveBaseUrl)
                        ?: throw IOException("nativeFetchModels returned null (JNI-level failure)")
                    parseModelList(result)
                } finally {
                    RustProvider.destroyProvider(handle)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                // Native 侧返回了 error，让异常传播以便上层能感知失败原因
                DebugLog.e(TAG, "fetchModels native error", e)
                throw e
            } catch (e: Exception) {
                DebugLog.e(TAG, "fetchModels failed", e)
                throw IOException("Unexpected error in fetchModels: ${e.message}", e)
            } catch (e: Throwable) {
                // 捕获逃逸的 Error（UnsatisfiedLinkError 等）防止闪退
                DebugLog.e(TAG, "Native fetchModels error", e)
                throw IOException("Native error in fetchModels: ${e.message}", e)
            }
        }

    private fun parseModelList(jsonStr: String): List<String> {
        return try {
            val response = json.decodeFromString<RustModelListResponse>(jsonStr)
            if (response.error != null) {
                DebugLog.e(TAG, "Native fetchModels error: ${response.error}")
                throw IOException("Native error: ${response.error}")
            }
            response.models.sorted()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse model list: $jsonStr", e)
            emptyList()
        } catch (e: Throwable) {
            // 捕获逃逸的 Error 防止闪退
            DebugLog.e(TAG, "Native error parsing model list: $jsonStr", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "RustOpenAiProvider"
    }
}
