// Anthropic Provider 实现
// 基于 Anthropic Messages API 的流式 SSE 生成

use std::collections::HashMap;
use std::fs;
use std::io::Read;

use async_trait::async_trait;
use futures::StreamExt;
use serde::Serialize;

use crate::api::http_client::AgoraHttpClient;
use crate::api::provider::LlmProvider;
use crate::api::sse::{append_utf8_safe, strip_sse_field, take_sse_block};
use crate::api::types::*;
use crate::error::AgoraError;

// ============================================================
// Anthropic 内部类型
// ============================================================

/// Anthropic 图像来源
#[derive(Debug, Clone, Serialize)]
struct AnthropicImageSource {
    #[serde(rename = "type")]
    source_type: String,
    media_type: String,
    data: String,
}

/// Anthropic 内容块
#[derive(Debug, Clone, Serialize)]
struct AnthropicContentBlock {
    #[serde(rename = "type")]
    block_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source: Option<AnthropicImageSource>,
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    input: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    thinking: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    signature: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_use_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    content: Option<String>,
}

/// Anthropic 消息（内部 wire 格式）
#[derive(Debug, Clone, Serialize)]
struct AnthropicApiMessage {
    role: String,
    content: Vec<AnthropicContentBlock>,
}

/// Anthropic Thinking 配置
#[derive(Debug, Clone, Serialize)]
struct AnthropicThinkingConfig {
    #[serde(rename = "type")]
    thinking_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    budget_tokens: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    display: Option<String>,
}

/// Anthropic Output Config
#[derive(Debug, Clone, Serialize)]
struct AnthropicOutputConfig {
    effort: String,
}

/// Anthropic 工具定义
#[derive(Debug, Clone, Serialize)]
struct AnthropicTool {
    name: String,
    description: String,
    input_schema: serde_json::Value,
}

/// Anthropic API 请求体
#[derive(Debug, Clone, Serialize)]
struct AnthropicApiRequest {
    model: String,
    messages: Vec<AnthropicApiMessage>,
    #[serde(skip_serializing_if = "Option::is_none")]
    system: Option<String>,
    max_tokens: i32,
    stream: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    thinking: Option<AnthropicThinkingConfig>,
    #[serde(skip_serializing_if = "Option::is_none")]
    output_config: Option<AnthropicOutputConfig>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tools: Option<Vec<AnthropicTool>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    temperature: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    top_p: Option<f32>,
}

/// Anthropic 模型列表响应
#[derive(Debug, Clone, serde::Deserialize)]
struct AnthropicModelsResponse {
    data: Vec<AnthropicModelInfo>,
    #[serde(default)]
    has_more: bool,
    #[serde(default)]
    last_id: Option<String>,
}

#[derive(Debug, Clone, serde::Deserialize)]
struct AnthropicModelInfo {
    id: String,
}

// ============================================================
// Claude 模型家族分类
// ============================================================

/// Claude 模型家族，用于确定请求格式（thinking / sampling 参数）
#[derive(Debug, PartialEq)]
enum ClaudeFamily {
    /// 不支持 thinking (claude-3-opus, claude-3-sonnet, claude-3-haiku, claude-3.5)
    NoThinking,
    /// 支持 budget_tokens 的旧代 (claude-3.7, claude-4.0, claude-4.1, claude-4.5)
    BudgetThinking,
    /// 4.6 过渡代：首选 adaptive，但 budget_tokens 仍可用
    Transitional4_6,
    /// 当代及未来模型 (opus-5, sonnet-5, fable, mythos 等)：仅 adaptive
    CurrentAdaptive,
}

fn classify_claude_family(model_name: &str) -> ClaudeFamily {
    let m = model_name.to_lowercase();
    if !m.starts_with("claude") {
        return ClaudeFamily::CurrentAdaptive;
    }

    // 3.0 / 3.5 不含 extended thinking
    if m.starts_with("claude-3-opus")
        || m.starts_with("claude-3-sonnet")
        || m.starts_with("claude-3-haiku")
        || m.starts_with("claude-3-5-")
    {
        return ClaudeFamily::NoThinking;
    }

    // 4.6: adaptive 首选，但 budget_tokens 仍可用
    if m.contains("4-6") || m.contains("4.6") {
        return ClaudeFamily::Transitional4_6;
    }

    // budget_tokens 代：3.7, 4.0, 4.1, 4.5
    if m.contains("claude-3-7")
        || m.contains("-4-0")
        || m.contains("-4-1")
        || m.contains("-4-5")
        || m.contains("4.0")
        || m.contains("4.1")
        || m.contains("4.5")
        || m.contains("-4-2025")
    {
        return ClaudeFamily::BudgetThinking;
    }

    ClaudeFamily::CurrentAdaptive
}

/// 将内部 thinking level 映射为 Anthropic effort 值
fn anthropic_effort(level: &str) -> String {
    match level.to_lowercase().as_str() {
        "none" | "minimal" => "low".to_string(),
        "low" | "medium" | "high" | "xhigh" | "max" => level.to_lowercase(),
        _ => "medium".to_string(),
    }
}

// ============================================================
// 图像编码辅助函数
// ============================================================

/// 根据文件扩展名返回 MIME 类型
fn mime_type_from_path(path: &str) -> &str {
    let lower = path.to_lowercase();
    if lower.ends_with(".png") {
        "image/png"
    } else if lower.ends_with(".webp") {
        "image/webp"
    } else if lower.ends_with(".gif") {
        "image/gif"
    } else {
        "image/jpeg"
    }
}

/// 读取图像文件并编码为 base64 字符串
fn encode_image_to_base64(image_path: &str) -> Option<(String, String)> {
    let mut file = fs::File::open(image_path).ok()?;
    let mut bytes = Vec::new();
    file.read_to_end(&mut bytes).ok()?;
    use base64::Engine;
    let b64 = base64::engine::general_purpose::STANDARD.encode(&bytes);
    let mime = mime_type_from_path(image_path);
    Some((mime.to_string(), b64))
}

// ============================================================
// 消息转换
// ============================================================

/// 将 ChatMessage 转换为 Anthropic 格式的 API 消息
fn convert_message(msg: &ChatMessage, include_images: bool) -> AnthropicApiMessage {
    let mut parts = Vec::new();

    // 图像（仅用户消息）
    if include_images && matches!(msg.participant, Participant::User) {
        for image_path in &msg.images {
            if let Some((mime_type, base64_data)) = encode_image_to_base64(image_path) {
                parts.push(AnthropicContentBlock {
                    block_type: "image".to_string(),
                    source: Some(AnthropicImageSource {
                        source_type: "base64".to_string(),
                        media_type: mime_type,
                        data: base64_data,
                    }),
                    text: None,
                    id: None,
                    name: None,
                    input: None,
                    thinking: None,
                    signature: None,
                    tool_use_id: None,
                    content: None,
                });
            }
        }
    }

    // 文本内容
    if !msg.text.trim().is_empty() {
        parts.push(AnthropicContentBlock {
            block_type: "text".to_string(),
            text: Some(msg.text.clone()),
            source: None,
            id: None,
            name: None,
            input: None,
            thinking: None,
            signature: None,
            tool_use_id: None,
            content: None,
        });
    }

    // 如果没有任何内容块，添加 "Continue"
    if parts.is_empty() {
        parts.push(AnthropicContentBlock {
            block_type: "text".to_string(),
            text: Some("Continue".to_string()),
            source: None,
            id: None,
            name: None,
            input: None,
            thinking: None,
            signature: None,
            tool_use_id: None,
            content: None,
        });
    }

    let role = match msg.participant {
        Participant::User => "user".to_string(),
        _ => "assistant".to_string(),
    };

    AnthropicApiMessage {
        role,
        content: parts,
    }
}

/// 将 ToolDefinition 转换为 Anthropic 格式
fn convert_tools(tools: &[ToolDefinition]) -> Vec<AnthropicTool> {
    tools
        .iter()
        .map(|td| {
            let params = &td.function.parameters;
            let mut properties_map = serde_json::Map::new();
            for (name, prop) in &params.properties {
                let mut prop_obj = serde_json::Map::new();
                prop_obj.insert(
                    "type".to_string(),
                    serde_json::Value::String(prop.r#type.clone()),
                );
                prop_obj.insert(
                    "description".to_string(),
                    serde_json::Value::String(prop.description.clone()),
                );
                if let Some(ref items) = prop.items {
                    let mut items_obj = serde_json::Map::new();
                    items_obj.insert(
                        "type".to_string(),
                        serde_json::Value::String(items.r#type.clone()),
                    );
                    items_obj.insert(
                        "description".to_string(),
                        serde_json::Value::String(items.description.clone()),
                    );
                    prop_obj.insert(
                        "items".to_string(),
                        serde_json::Value::Object(items_obj),
                    );
                }
                properties_map.insert(name.clone(), serde_json::Value::Object(prop_obj));
            }

            let required: Vec<serde_json::Value> = params
                .required
                .iter()
                .map(|r| serde_json::Value::String(r.clone()))
                .collect();

            AnthropicTool {
                name: td.function.name.clone(),
                description: td.function.description.clone(),
                input_schema: serde_json::Value::Object(serde_json::Map::from_iter([
                    ("type".to_string(), serde_json::Value::String(params.r#type.clone())),
                    ("properties".to_string(), serde_json::Value::Object(properties_map)),
                    ("required".to_string(), serde_json::Value::Array(required)),
                ])),
            }
        })
        .collect()
}

// ============================================================
// AnthropicProvider
// ============================================================

/// Anthropic API 提供商
pub struct AnthropicProvider {
    name: String,
    default_base_url: String,
}

impl AnthropicProvider {
    pub fn new() -> Self {
        Self {
            name: "Anthropic".to_string(),
            default_base_url: "https://api.anthropic.com/v1".to_string(),
        }
    }

    /// 构建请求 headers
    fn build_headers(api_key: &str) -> HashMap<String, String> {
        let mut headers = HashMap::new();
        headers.insert("Content-Type".to_string(), "application/json".to_string());
        headers.insert("x-api-key".to_string(), api_key.to_string());
        headers.insert("anthropic-version".to_string(), "2023-06-01".to_string());
        headers
    }

    /// 解析 SSE 事件流，返回 StreamEvent 序列
    async fn parse_sse_stream(
        stream_response: &mut crate::api::http_client::StreamResponse,
    ) -> Result<Vec<StreamEvent>, AgoraError> {
        let mut events: Vec<StreamEvent> = Vec::new();
        let mut raw_buffer = String::new();
        let mut remainder = Vec::new();

        // 流式累积状态
        let mut tool_use_id: Option<String> = None;
        let mut tool_use_name: Option<String> = None;
        let mut tool_use_args = String::new();
        let mut thinking_signature: Option<String> = None;
        let mut input_tokens: i32 = 0;

        let byte_stream = stream_response.stream();

        while let Some(chunk_result) = byte_stream.next().await {
            match chunk_result {
                Ok(bytes) => {
                    append_utf8_safe(&mut raw_buffer, &mut remainder, &bytes);

                    while let Some(block) = take_sse_block(&mut raw_buffer) {
                        // 解析 SSE 事件行
                        let mut event_type: Option<String> = None;
                        let mut data_json: Option<String> = None;

                        for line in block.lines() {
                            if let Some(val) = strip_sse_field(line, "event") {
                                event_type = Some(val.to_string());
                            } else if let Some(val) = strip_sse_field(line, "data") {
                                data_json = Some(val.to_string());
                            }
                        }

                        if let Some(ref data_str) = data_json {
                            // 解析 SSE 事件 JSON
                            let event: AnthropicStreamEvent =
                                match serde_json::from_str(data_str) {
                                    Ok(e) => e,
                                    Err(_) => continue,
                                };

                            match event.r#type.as_deref() {
                                Some("message_start") => {
                                    if let Some(ref msg) = event.message {
                                        if let Some(usage) = msg.get("usage") {
                                            if let Some(it) = usage.get("input_tokens").and_then(|v| v.as_i64()) {
                                                input_tokens = it as i32;
                                            }
                                        }
                                    }
                                }

                                Some("content_block_start") => {
                                    if let Some(ref block) = event.content_block {
                                        let block_type = block
                                            .get("type")
                                            .and_then(|v| v.as_str())
                                            .unwrap_or("");

                                        match block_type {
                                            "thinking" => {
                                                thinking_signature = block
                                                    .get("signature")
                                                    .and_then(|v| v.as_str())
                                                    .filter(|s| !s.is_empty())
                                                    .map(|s| s.to_string());
                                            }
                                            "tool_use" => {
                                                tool_use_id = block
                                                    .get("id")
                                                    .and_then(|v| v.as_str())
                                                    .map(|s| s.to_string());
                                                tool_use_name = block
                                                    .get("name")
                                                    .and_then(|v| v.as_str())
                                                    .map(|s| s.to_string());
                                                tool_use_args.clear();
                                            }
                                            _ => {}
                                        }
                                    }
                                }

                                Some("content_block_delta") => {
                                    if let Some(ref delta) = event.delta {
                                        let delta_type = delta
                                            .get("type")
                                            .and_then(|v| v.as_str())
                                            .unwrap_or("");

                                        match delta_type {
                                            "input_json_delta" => {
                                                if let Some(json) = delta
                                                    .get("partial_json")
                                                    .and_then(|v| v.as_str())
                                                {
                                                    tool_use_args.push_str(json);
                                                }
                                            }
                                            "signature_delta" => {
                                                if let Some(sig) = delta
                                                    .get("signature")
                                                    .and_then(|v| v.as_str())
                                                    .filter(|s| !s.is_empty())
                                                {
                                                    thinking_signature = Some(sig.to_string());
                                                    events.push(StreamEvent::ThoughtChunk {
                                                        thought: String::new(),
                                                        title: None,
                                                        signature: Some(sig.to_string()),
                                                    });
                                                }
                                            }
                                            _ => {
                                                // text_delta 或 thinking_delta
                                                if let Some(text) = delta
                                                    .get("text")
                                                    .and_then(|v| v.as_str())
                                                {
                                                    events.push(StreamEvent::TextChunk {
                                                        text: text.to_string(),
                                                    });
                                                }
                                                if let Some(thinking) = delta
                                                    .get("thinking")
                                                    .and_then(|v| v.as_str())
                                                {
                                                    if let Some(sig) = delta
                                                        .get("signature")
                                                        .and_then(|v| v.as_str())
                                                    {
                                                        thinking_signature = Some(sig.to_string());
                                                    }
                                                    events.push(StreamEvent::ThoughtChunk {
                                                        thought: thinking.to_string(),
                                                        title: None,
                                                        signature: thinking_signature.clone(),
                                                    });
                                                }
                                            }
                                        }
                                    }
                                }

                                Some("content_block_stop") => {
                                    if let (Some(ref id), Some(ref name)) =
                                        (&tool_use_id, &tool_use_name)
                                    {
                                        events.push(StreamEvent::ToolCallRequest {
                                            id: id.clone(),
                                            name: name.clone(),
                                            arguments: tool_use_args.clone(),
                                            signature: thinking_signature.take(),
                                        });
                                    }
                                    tool_use_id = None;
                                    tool_use_name = None;
                                    thinking_signature = None;
                                }

                                Some("message_delta") => {
                                    if let Some(ref usage) = event.usage {
                                        if let Some(ot) = usage
                                            .get("output_tokens")
                                            .and_then(|v| v.as_i64())
                                        {
                                            let total = input_tokens + (ot as i32);
                                            events.push(StreamEvent::UsageUpdate {
                                                token_count: total,
                                                thoughts_token_count: 0,
                                            });
                                        }
                                    }
                                }

                                Some("message_stop") => {
                                    // 消息结束，无需特殊处理
                                }

                                Some("ping") => {
                                    // 心跳，忽略
                                }

                                Some("error") => {
                                    if let Some(ref err) = event.error {
                                        let code = err
                                            .get("type")
                                            .and_then(|v| v.as_str())
                                            .unwrap_or("unknown")
                                            .to_string();
                                        let message = err
                                            .get("message")
                                            .and_then(|v| v.as_str())
                                            .unwrap_or("Unknown error")
                                            .to_string();
                                        events.push(StreamEvent::Error {
                                            error: GenerationError::Api {
                                                code,
                                                message,
                                                error_type: None,
                                            },
                                        });
                                    }
                                }

                                _ => {}
                            }
                        }
                    }
                }
                Err(e) => {
                    return Err(AgoraError::Stream(format!(
                        "SSE stream error: {}",
                        e
                    )));
                }
            }
        }

        Ok(events)
    }
}

#[async_trait]
impl LlmProvider for AnthropicProvider {
    fn name(&self) -> &str {
        &self.name
    }

    fn default_base_url(&self) -> &str {
        &self.default_base_url
    }

    async fn generate_response(
        &self,
        messages: &[ChatMessage],
        config: &ProviderConfig,
        client: &AgoraHttpClient,
    ) -> Result<Vec<StreamEvent>, AgoraError> {
        let base_url = config
            .base_url
            .as_deref()
            .map(|s| s.trim_end_matches('/'))
            .filter(|s| !s.is_empty())
            .unwrap_or(&self.default_base_url);

        let model_name = &config.model_id;

        // ── 模型家族分类 ──
        let family = classify_claude_family(model_name);

        // ── Thinking 配置 ──
        let thinking_budget = config
            .thinking_budget_tokens
            .clamp(1024, 128000);

        let thinking_config = if !config.thinking_enabled || !model_name.starts_with("claude") {
            None
        } else {
            match family {
                ClaudeFamily::NoThinking => None,
                ClaudeFamily::BudgetThinking => Some(AnthropicThinkingConfig {
                    thinking_type: "enabled".to_string(),
                    budget_tokens: Some(thinking_budget),
                    display: Some("summarized".to_string()),
                }),
                ClaudeFamily::Transitional4_6 if config.thinking_budget_enabled => {
                    Some(AnthropicThinkingConfig {
                        thinking_type: "enabled".to_string(),
                        budget_tokens: Some(thinking_budget),
                        display: Some("summarized".to_string()),
                    })
                }
                _ => Some(AnthropicThinkingConfig {
                    thinking_type: "adaptive".to_string(),
                    budget_tokens: None,
                    display: Some("summarized".to_string()),
                }),
            }
        };

        let output_config = if thinking_config
            .as_ref()
            .map(|t| t.thinking_type == "adaptive")
            .unwrap_or(false)
        {
            Some(AnthropicOutputConfig {
                effort: anthropic_effort(&config.thinking_level),
            })
        } else {
            None
        };

        // temperature/top_p 仅在旧代/过渡代可用
        let allows_sampling_params = family != ClaudeFamily::CurrentAdaptive;

        // ── 消息转换 ──
        let api_messages: Vec<AnthropicApiMessage> = messages
            .iter()
            .map(|msg| convert_message(msg, config.include_images))
            .collect();

        // ── 工具定义转换 ──
        let anthropic_tools = config
            .tools
            .as_ref()
            .map(|tools| convert_tools(tools));

        // ── max_tokens ──
        let computed_max_tokens = config.max_tokens.unwrap_or_else(|| {
            if let Some(ref tc) = thinking_config {
                if let Some(budget) = tc.budget_tokens {
                    std::cmp::max(budget + 1024, 4096)
                } else if tc.thinking_type == "adaptive" {
                    16384
                } else {
                    4096
                }
            } else {
                4096
            }
        });

        // ── 构建请求体 ──
        let request_body = AnthropicApiRequest {
            model: model_name.clone(),
            messages: api_messages,
            system: config.system_prompt.clone(),
            max_tokens: computed_max_tokens,
            stream: true,
            thinking: thinking_config,
            output_config,
            tools: anthropic_tools,
            temperature: config.temperature.filter(|_| allows_sampling_params),
            top_p: config.top_p.filter(|_| allows_sampling_params),
        };

        let json_body = serde_json::to_value(&request_body)?;

        let url = format!("{}/messages", base_url);
        let headers = Self::build_headers(&config.api_key);

        let mut stream_response = client.stream_post(&url, &json_body, &headers).await?;

        Self::parse_sse_stream(&mut stream_response).await
    }

    async fn fetch_models(
        &self,
        api_key: &str,
        base_url: Option<&str>,
        client: &AgoraHttpClient,
    ) -> Result<Vec<String>, AgoraError> {
        let effective_base_url = base_url
            .map(|s| s.trim_end_matches('/'))
            .filter(|s| !s.is_empty())
            .unwrap_or(&self.default_base_url);

        let headers = Self::build_headers(api_key);
        let mut all_models: Vec<String> = Vec::new();
        let mut after_id: Option<String> = None;
        let mut pages = 0;

        // 分页获取模型列表
        while pages < 10 {
            let mut url = format!("{}/models?limit=100", effective_base_url);
            if let Some(ref after) = after_id {
                url.push_str("&after_id=");
                url.push_str(after);
            }

            let response_text = match client.fetch_models(&url, &headers).await {
                Ok(body) => body,
                Err(_) => break,
            };

            let page: AnthropicModelsResponse = match serde_json::from_str(&response_text) {
                Ok(p) => p,
                Err(_) => break,
            };

            for model_info in &page.data {
                all_models.push(model_info.id.clone());
            }

            if !page.has_more || page.data.is_empty() {
                break;
            }

            after_id = page
                .last_id
                .or_else(|| page.data.last().map(|m| m.id.clone()));
            pages += 1;
        }

        if all_models.is_empty() {
            // Anthropic 没有模型列表端点时返回硬编码列表
            return Ok(vec![
                "claude-opus-4-20250514".to_string(),
                "claude-sonnet-4-20250514".to_string(),
                "claude-3-5-sonnet-20241022".to_string(),
                "claude-3-5-haiku-20241022".to_string(),
                "claude-3-opus-20240229".to_string(),
                "claude-3-sonnet-20240229".to_string(),
                "claude-3-haiku-20240307".to_string(),
            ]);
        }

        all_models.sort();
        Ok(all_models)
    }
}

impl Default for AnthropicProvider {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================
// 测试
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_classify_no_thinking() {
        assert_eq!(
            classify_claude_family("claude-3-opus-20240229"),
            ClaudeFamily::NoThinking
        );
        assert_eq!(
            classify_claude_family("claude-3-5-sonnet-20241022"),
            ClaudeFamily::NoThinking
        );
        assert_eq!(
            classify_claude_family("claude-3-haiku-20240307"),
            ClaudeFamily::NoThinking
        );
    }

    #[test]
    fn test_classify_budget_thinking() {
        assert_eq!(
            classify_claude_family("claude-3-7-sonnet-20250219"),
            ClaudeFamily::BudgetThinking
        );
        assert_eq!(
            classify_claude_family("claude-4-0-20250514"),
            ClaudeFamily::BudgetThinking
        );
        assert_eq!(
            classify_claude_family("claude-4-5-opus-20250514"),
            ClaudeFamily::BudgetThinking
        );
    }

    #[test]
    fn test_classify_transitional_4_6() {
        assert_eq!(
            classify_claude_family("claude-4-6-sonnet-20250714"),
            ClaudeFamily::Transitional4_6
        );
    }

    #[test]
    fn test_classify_current_adaptive() {
        assert_eq!(
            classify_claude_family("claude-opus-5-2026"),
            ClaudeFamily::CurrentAdaptive
        );
        assert_eq!(
            classify_claude_family("claude-sonnet-5"),
            ClaudeFamily::CurrentAdaptive
        );
        assert_eq!(classify_claude_family("gpt-4"), ClaudeFamily::CurrentAdaptive);
    }

    #[test]
    fn test_anthropic_effort() {
        assert_eq!(anthropic_effort("none"), "low");
        assert_eq!(anthropic_effort("minimal"), "low");
        assert_eq!(anthropic_effort("low"), "low");
        assert_eq!(anthropic_effort("medium"), "medium");
        assert_eq!(anthropic_effort("high"), "high");
        assert_eq!(anthropic_effort("xhigh"), "xhigh");
        assert_eq!(anthropic_effort("max"), "max");
        assert_eq!(anthropic_effort("unknown"), "medium");
    }

    #[test]
    fn test_mime_type_from_path() {
        assert_eq!(mime_type_from_path("photo.png"), "image/png");
        assert_eq!(mime_type_from_path("photo.PNG"), "image/png");
        assert_eq!(mime_type_from_path("photo.webp"), "image/webp");
        assert_eq!(mime_type_from_path("photo.gif"), "image/gif");
        assert_eq!(mime_type_from_path("photo.jpg"), "image/jpeg");
        assert_eq!(mime_type_from_path("photo.jpeg"), "image/jpeg");
        assert_eq!(mime_type_from_path("photo.bmp"), "image/jpeg");
    }
}