package com.newoether.agora.api

import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.Participant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared serialisable types used by all Rust-backed providers.
 *
 * These map 1-to-1 with the Rust-side types in `rust-core/src/api/types.rs`
 * and exist so the Kotlin glue layer can encode/decode the JSON wire format
 * without pulling in Rust-specific naming conventions.
 */

// ── Provider configuration (Kotlin → Rust) ──────────────────────────

@Serializable
data class RustProviderConfig(
    @SerialName("api_key") val apiKey: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("max_context_window") val maxContextWindow: Int = 20,
    @SerialName("code_execution_enabled") val codeExecutionEnabled: Boolean = false,
    @SerialName("google_search_enabled") val googleSearchEnabled: Boolean = false,
    @SerialName("thinking_enabled") val thinkingEnabled: Boolean = true,
    @SerialName("thinking_level") val thinkingLevel: String = "medium",
    @SerialName("thinking_budget_enabled") val thinkingBudgetEnabled: Boolean = false,
    @SerialName("thinking_budget_tokens") val thinkingBudgetTokens: Int = 4096,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("include_images") val includeImages: Boolean = true,
    @SerialName("temperature") val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
    @SerialName("presence_penalty") val presencePenalty: Float? = null,
)

// ── Chat message (Kotlin → Rust) ────────────────────────────────────

@Serializable
data class RustChatMessage(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    val text: String,
    val images: List<String> = emptyList(),
    val participant: String,
    val timestamp: Long = 0,
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("tool_call_json") val toolCallJson: String? = null,
)

fun ChatMessage.toRustMessage(): RustChatMessage = RustChatMessage(
    id = id,
    parentId = parentId,
    text = text,
    images = images,
    participant = participant.name,
    timestamp = timestamp,
    modelName = modelName,
    toolCallJson = toolCall?.let { tc ->
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer(),
            mapOf(
                "toolName" to tc.toolName,
                "arguments" to tc.arguments,
                "result" to tc.result,
                "toolCallId" to (tc.toolCallId ?: ""),
                "signature" to (tc.signature ?: "")
            )
        )
    }
)

// ── Stream events (Rust → Kotlin) ───────────────────────────────────

/**
 * Mirrors the Rust `StreamEvent` tagged-union JSON format.
 * The Rust side serialises as `{"type": "<variant>", "data": {…}}`.
 */
@Serializable
data class RustStreamEvent(
    val type: String,
    val data: RustStreamEventData? = null,
    // Some events carry fields at the top level (e.g. "error" might be a nested object)
    val text: String? = null,
    val thought: String? = null,
    val title: String? = null,
    val signature: String? = null,
    @SerialName("token_count") val tokenCount: Int? = null,
    @SerialName("thoughts_token_count") val thoughtsTokenCount: Int? = null,
    val error: RustGenerationError? = null,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val calls: List<RustToolCallRequest>? = null,
    val attempt: Int? = null,
    @SerialName("max_attempts") val maxAttempts: Int? = null,
)

@Serializable
data class RustStreamEventData(
    val text: String? = null,
    val thought: String? = null,
    val title: String? = null,
    val signature: String? = null,
    @SerialName("token_count") val tokenCount: Int? = null,
    @SerialName("thoughts_token_count") val thoughtsTokenCount: Int? = null,
    val error: RustGenerationError? = null,
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val calls: List<RustToolCallRequest>? = null,
    val attempt: Int? = null,
    @SerialName("max_attempts") val maxAttempts: Int? = null,
)

@Serializable
data class RustGenerationError(
    val type: String? = null,
    val code: String? = null,
    val message: String? = null,
    @SerialName("status_code") val statusCode: Int? = null,
    val provider: String? = null,
    val violations: List<String>? = null,
)

@Serializable
data class RustToolCallRequest(
    val id: String,
    val name: String,
    val arguments: String,
    val signature: String? = null,
)

fun RustStreamEvent.toStreamEvent(): StreamEvent? {
    return when (type) {
        "text_chunk" -> {
            val t = data?.text ?: text ?: return null
            StreamEvent.TextChunk(t)
        }
        "thought_chunk" -> {
            val th = data?.thought ?: thought ?: ""
            val ti = data?.title ?: title
            val sig = data?.signature ?: signature
            StreamEvent.ThoughtChunk(thought = th, title = ti, signature = sig)
        }
        "usage_update" -> {
            val total = data?.tokenCount ?: tokenCount ?: 0
            val thoughts = data?.thoughtsTokenCount ?: thoughtsTokenCount ?: 0
            StreamEvent.UsageUpdate(tokenCount = total, thoughtsTokenCount = thoughts)
        }
        "error" -> {
            val err = data?.error ?: error
            val genError = when (err?.type) {
                "api" -> GenerationError.Api(
                    code = err.code,
                    type = null,
                    message = err.message ?: "Unknown API error"
                )
                "network" -> GenerationError.Network(
                    statusCode = err.statusCode ?: 0,
                    message = err.message ?: "Network error"
                )
                "timeout" -> GenerationError.Timeout
                "request_format" -> GenerationError.RequestFormat(
                    provider = err.provider ?: "unknown",
                    details = err.violations?.joinToString() ?: err.message ?: "Invalid request"
                )
                else -> GenerationError.Unknown(
                    RuntimeException(err?.message ?: "Unknown Rust error")
                )
            }
            StreamEvent.Error(genError)
        }
        "tool_call_request" -> {
            val d = data
            val callId = d?.id ?: id ?: ""
            val callName = d?.name ?: name ?: ""
            val callArgs = d?.arguments ?: arguments ?: "{}"
            val callSig = d?.signature ?: signature
            StreamEvent.ToolCallRequest(id = callId, name = callName, arguments = callArgs, signature = callSig)
        }
        "tool_calls_request" -> {
            val rustCalls = data?.calls ?: calls ?: return null
            val mapped = rustCalls.map { c ->
                StreamEvent.ToolCallRequest(c.id, c.name, c.arguments, c.signature)
            }
            if (mapped.size == 1) mapped.first()
            else StreamEvent.ToolCallsRequest(mapped)
        }
        "retrying" -> {
            val a = data?.attempt ?: attempt ?: return null
            val m = data?.maxAttempts ?: maxAttempts ?: 3
            StreamEvent.Retrying(attempt = a, maxAttempts = m)
        }
        else -> null
    }
}

// ── Model list response ─────────────────────────────────────────────

@Serializable
data class RustModelListResponse(
    val models: List<String> = emptyList(),
    val error: String? = null,
)
