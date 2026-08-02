use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ============================================================
// StreamEvent — 流式事件（对应 Kotlin StreamEvent sealed class）
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "data")]
pub enum StreamEvent {
    #[serde(rename = "text_chunk")]
    TextChunk { text: String },

    #[serde(rename = "thought_chunk")]
    ThoughtChunk {
        thought: String,
        title: Option<String>,
        signature: Option<String>,
    },

    #[serde(rename = "usage_update")]
    UsageUpdate {
        token_count: i32,
        thoughts_token_count: i32,
    },

    #[serde(rename = "error")]
    Error { error: GenerationError },

    #[serde(rename = "tool_call_request")]
    ToolCallRequest {
        id: String,
        name: String,
        arguments: String,
        signature: Option<String>,
    },

    #[serde(rename = "tool_calls_request")]
    ToolCallsRequest { calls: Vec<ToolCallRequestData> },

    #[serde(rename = "retrying")]
    Retrying {
        attempt: i32,
        max_attempts: i32,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCallRequestData {
    pub id: String,
    pub name: String,
    pub arguments: String,
    pub signature: Option<String>,
}

// ============================================================
// GenerationError — 生成错误
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum GenerationError {
    #[serde(rename = "api")]
    Api {
        code: String,
        message: String,
        error_type: Option<String>,
    },
    #[serde(rename = "network")]
    Network { status_code: i32, message: String },
    #[serde(rename = "timeout")]
    Timeout,
    #[serde(rename = "request_format")]
    RequestFormat {
        provider: String,
        violations: Vec<String>,
    },
    #[serde(rename = "sse_parse")]
    SseParse {
        raw_line: String,
        cause: String,
    },
    #[serde(rename = "tool_execution")]
    ToolExecution {
        tool_name: String,
        arguments: String,
        message: String,
    },
    #[serde(rename = "transcription")]
    Transcription {
        image_path: String,
        message: String,
    },
    #[serde(rename = "embedding")]
    Embedding {
        model_id: String,
        message: String,
    },
    #[serde(rename = "local_model")]
    LocalModel { message: String },
    #[serde(rename = "configuration")]
    Configuration { message: String },
    #[serde(rename = "cancelled")]
    Cancelled,
    #[serde(rename = "unknown")]
    Unknown { message: String },
}

impl GenerationError {
    pub fn user_message(&self) -> String {
        match self {
            GenerationError::Api { message, .. } => message.clone(),
            GenerationError::Network { message, .. } => message.clone(),
            GenerationError::Timeout => "Request timed out".to_string(),
            GenerationError::RequestFormat { violations, .. } => violations.join("; "),
            GenerationError::SseParse { .. } => "Failed to parse server response.".to_string(),
            GenerationError::ToolExecution { tool_name, message, .. } => {
                format!("Tool '{}' failed: {}", tool_name, message)
            }
            GenerationError::Transcription { message, .. } => {
                format!("Image transcription failed: {}", message)
            }
            GenerationError::Embedding { message, .. } => {
                format!("Embedding failed: {}", message)
            }
            GenerationError::LocalModel { message } => message.clone(),
            GenerationError::Configuration { message } => message.clone(),
            GenerationError::Cancelled => "Generation cancelled.".to_string(),
            GenerationError::Unknown { message } => message.clone(),
        }
    }
}

// ============================================================
// ProviderConfig — Provider 配置
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderConfig {
    pub api_key: String,
    pub model_id: String,
    #[serde(default)]
    pub system_prompt: Option<String>,
    #[serde(default = "default_max_context_window")]
    pub max_context_window: i32,
    #[serde(default)]
    pub code_execution_enabled: bool,
    #[serde(default)]
    pub google_search_enabled: bool,
    #[serde(default = "default_true")]
    pub thinking_enabled: bool,
    #[serde(default = "default_thinking_level")]
    pub thinking_level: String,
    #[serde(default)]
    pub thinking_budget_enabled: bool,
    #[serde(default = "default_thinking_budget")]
    pub thinking_budget_tokens: i32,
    #[serde(default)]
    pub base_url: Option<String>,
    #[serde(default)]
    pub tools: Option<Vec<ToolDefinition>>,
    #[serde(default)]
    pub user_prepend: Option<String>,
    #[serde(default)]
    pub user_postpend: Option<String>,
    #[serde(default = "default_true")]
    pub include_images: bool,
    #[serde(default)]
    pub temperature: Option<f32>,
    #[serde(default)]
    pub max_tokens: Option<i32>,
    #[serde(default)]
    pub top_p: Option<f32>,
    #[serde(default)]
    pub frequency_penalty: Option<f32>,
    #[serde(default)]
    pub presence_penalty: Option<f32>,
}

fn default_max_context_window() -> i32 { 20 }
fn default_true() -> bool { true }
fn default_thinking_level() -> String { "medium".to_string() }
fn default_thinking_budget() -> i32 { 4096 }

// ============================================================
// ToolDefinition — 工具定义（OpenAI 兼容格式）
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolDefinition {
    #[serde(default = "default_tool_type")]
    pub r#type: String,
    pub function: ToolFunction,
}

fn default_tool_type() -> String { "function".to_string() }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolFunction {
    pub name: String,
    pub description: String,
    pub parameters: ToolParameters,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolParameters {
    #[serde(default = "default_object_type")]
    pub r#type: String,
    pub properties: HashMap<String, ToolProperty>,
    #[serde(default)]
    pub required: Vec<String>,
}

fn default_object_type() -> String { "object".to_string() }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolProperty {
    pub r#type: String,
    pub description: String,
    #[serde(default)]
    pub items: Option<Box<ToolProperty>>,
}

// ============================================================
// ChatMessage — 聊天消息
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub id: String,
    #[serde(default)]
    pub parent_id: Option<String>,
    pub text: String,
    #[serde(default)]
    pub images: Vec<String>,
    #[serde(default)]
    pub thoughts: Option<String>,
    #[serde(default)]
    pub thought_title: Option<String>,
    #[serde(default)]
    pub token_count: i32,
    #[serde(default = "default_message_status")]
    pub status: MessageStatus,
    pub participant: Participant,
    #[serde(default)]
    pub timestamp: i64,
    #[serde(default)]
    pub thought_time_ms: Option<i64>,
    #[serde(default)]
    pub model_name: Option<String>,
    #[serde(default)]
    pub tool_call_json: Option<String>,
    #[serde(default)]
    pub attachment_meta: Option<String>,
    #[serde(default)]
    pub retry_text: Option<String>,
}

fn default_message_status() -> MessageStatus { MessageStatus::Success }

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum Participant {
    #[serde(rename = "USER")]
    User,
    #[serde(rename = "MODEL")]
    Model,
    #[serde(rename = "ERROR")]
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum MessageStatus {
    #[serde(rename = "TRANSCRIBING")]
    Transcribing,
    #[serde(rename = "SENDING")]
    Sending,
    #[serde(rename = "THINKING")]
    Thinking,
    #[serde(rename = "TOOL_CALLING")]
    ToolCalling,
    #[serde(rename = "SUCCESS")]
    Success,
    #[serde(rename = "STOPPED")]
    Stopped,
    #[serde(rename = "ERROR")]
    Error,
}

// ============================================================
// OpenAI 格式请求/响应类型
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiChatRequest {
    pub model: String,
    pub messages: Vec<OpenAiMessage>,
    #[serde(default = "default_true")]
    pub stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream_options: Option<OpenAiStreamOptions>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<ToolDefinition>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning_effort: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning: Option<OpenAiReasoning>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub top_p: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub frequency_penalty: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub presence_penalty: Option<f32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiStreamOptions {
    pub include_usage: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiReasoning {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub effort: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_tokens: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiMessage {
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub content: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<OpenAiRequestToolCall>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reasoning_content: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiRequestToolCall {
    pub id: String,
    #[serde(default = "default_tool_type")]
    pub r#type: String,
    pub function: OpenAiRequestFunction,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiRequestFunction {
    pub name: String,
    pub arguments: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiContentPart {
    pub r#type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_url: Option<OpenAiImageUrl>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiImageUrl {
    pub url: String,
}

// ============================================================
// OpenAI SSE 流式响应
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiStreamResponse {
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub choices: Option<Vec<OpenAiChoice>>,
    #[serde(default)]
    pub usage: Option<OpenAiUsage>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiChoice {
    pub index: i32,
    #[serde(default)]
    pub delta: Option<OpenAiDelta>,
    #[serde(default)]
    pub finish_reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiDelta {
    #[serde(default)]
    pub role: Option<String>,
    #[serde(default)]
    pub content: Option<String>,
    #[serde(default, alias = "reasoning_content")]
    pub reasoning: Option<String>,
    /// OpenRouter sends `reasoning_details` as an array of objects with `type` and `text` fields.
    #[serde(default)]
    pub reasoning_details: Option<Vec<OpenAiReasoningDetail>>,
    #[serde(default)]
    pub tool_calls: Option<Vec<OpenAiToolCallDelta>>,
}

/// OpenRouter reasoning detail item (matches Kotlin OpenAiReasoningDetail)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiReasoningDetail {
    #[serde(default)]
    pub r#type: Option<String>,
    #[serde(default)]
    pub text: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiToolCallDelta {
    #[serde(default)]
    pub index: Option<i32>,
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default, rename = "type")]
    pub call_type: Option<String>,
    #[serde(default)]
    pub function: Option<OpenAiFunctionCallDelta>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiFunctionCallDelta {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub arguments: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiUsage {
    pub prompt_tokens: i32,
    pub completion_tokens: i32,
    #[serde(default)]
    pub total_tokens: i32,
    #[serde(default)]
    pub completion_tokens_details: Option<OpenAiCompletionTokensDetails>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiCompletionTokensDetails {
    #[serde(default)]
    pub reasoning_tokens: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiModelListResponse {
    pub data: Vec<OpenAiModelInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiModelInfo {
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiErrorResponse {
    pub error: OpenAiErrorDetail,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenAiErrorDetail {
    pub message: String,
    #[serde(default)]
    pub r#type: Option<String>,
    #[serde(default)]
    pub code: Option<String>,
}

// ============================================================
// PendingToolCall — 流式工具调用累积器
// ============================================================

#[derive(Debug, Clone, Default)]
pub struct PendingToolCall {
    pub id: String,
    pub name: String,
    pub args: String,
}

// ============================================================
// Embedding 请求/响应
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddingRequest {
    pub input: serde_json::Value,
    pub model: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddingResponse {
    pub data: Vec<EmbeddingData>,
    pub model: String,
    #[serde(default)]
    pub usage: Option<EmbeddingUsage>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddingData {
    pub embedding: Vec<f32>,
    pub index: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmbeddingUsage {
    pub prompt_tokens: i32,
    pub total_tokens: i32,
}

// ============================================================
// Anthropic 格式类型
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnthropicRequest {
    pub model: String,
    pub messages: Vec<AnthropicMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub system: Option<String>,
    #[serde(default = "default_max_tokens_anthropic")]
    pub max_tokens: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub temperature: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub top_p: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stop_sequences: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<serde_json::Value>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thinking: Option<serde_json::Value>,
}

fn default_max_tokens_anthropic() -> i32 { 8192 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnthropicMessage {
    pub role: String,
    pub content: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnthropicStreamEvent {
    #[serde(default)]
    pub r#type: Option<String>,
    #[serde(default)]
    pub delta: Option<serde_json::Value>,
    #[serde(default)]
    pub content_block: Option<serde_json::Value>,
    #[serde(default)]
    pub index: Option<i32>,
    #[serde(default)]
    pub message: Option<serde_json::Value>,
    #[serde(default)]
    pub usage: Option<serde_json::Value>,
    #[serde(default)]
    pub error: Option<serde_json::Value>,
}

// ============================================================
// Gemini 格式类型
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiRequest {
    pub contents: Vec<GeminiContent>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub system_instruction: Option<GeminiContent>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub generation_config: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<serde_json::Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiContent {
    pub role: String,
    pub parts: Vec<GeminiPart>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiPart {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub inline_data: Option<GeminiInlineData>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub function_call: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub function_response: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiInlineData {
    pub mime_type: String,
    pub data: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiStreamResponse {
    #[serde(default)]
    pub candidates: Option<Vec<GeminiCandidate>>,
    #[serde(default)]
    pub usage_metadata: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiCandidate {
    #[serde(default)]
    pub content: Option<GeminiContent>,
    #[serde(default)]
    pub finish_reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiModelListResponse {
    #[serde(default)]
    pub models: Vec<GeminiModelInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeminiModelInfo {
    pub name: String,
    #[serde(default)]
    pub display_name: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
}

// ============================================================
// Ollama 格式类型
// ============================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OllamaChatRequest {
    pub model: String,
    pub messages: Vec<OllamaMessage>,
    #[serde(default)]
    pub stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub options: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tools: Option<Vec<serde_json::Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OllamaMessage {
    pub role: String,
    pub content: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub images: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<serde_json::Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OllamaChatResponse {
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub message: Option<OllamaMessageResponse>,
    #[serde(default)]
    pub done: Option<bool>,
    #[serde(default)]
    pub total_duration: Option<i64>,
    #[serde(default)]
    pub eval_count: Option<i32>,
    #[serde(default)]
    pub prompt_eval_count: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OllamaMessageResponse {
    pub role: String,
    pub content: String,
    #[serde(default)]
    pub thinking: Option<String>,
    #[serde(default)]
    pub tool_calls: Option<Vec<serde_json::Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OllamaModelListResponse {
    #[serde(default)]
    pub models: Vec<OllamaModelInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OllamaModelInfo {
    pub name: String,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub size: Option<i64>,
}
