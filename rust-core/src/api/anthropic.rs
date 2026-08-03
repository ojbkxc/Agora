// Anthropic Provider 实现
// 基于 Anthropic Messages API 的流式 SSE 生成

use std::collections::HashMap;
use std::fs;
use std::io::Read;

use async_trait::async_trait;
use futures::StreamExt;
use serde::Serialize;

use crate::api::http_client::AgoraHttpClient;
use crate::api::message_pipeline;
use crate::api::provider::{LlmProvider, StreamCallback};
use crate::api::request_validator;
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
    content: Option<serde_json::Value>,
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

/// 检查工具消息是否与 Anthropic 的签名思考兼容。
///
/// 对应 Kotlin AnthropicProvider.isAnthropicToolRoundCompatible。
/// 当 thinking 启用时，带有思考内容的工具消息必须携带有效签名。
fn is_anthropic_tool_round_compatible(msg: &ChatMessage) -> bool {
    // 如果没有思考内容，则兼容
    let thoughts = match &msg.thoughts {
        Some(t) if !t.trim().is_empty() => t,
        _ => return true,
    };

    // 检查签名：从 tool_call_json 中提取签名
    if let Some(ref tcj) = msg.tool_call_json {
        if let Ok(tc) = serde_json::from_str::<serde_json::Value>(tcj) {
            if let Some(sig) = tc
                .get("signature")
                .or_else(|| tc.get("thought_signature"))
                .and_then(|v| v.as_str())
            {
                if !sig.is_empty() {
                    return true;
                }
            }
        }
    }

    // 有思考内容但没有有效签名 → 不兼容
    false
}

/// 合并连续的 result_ 消息（Anthropic 要求将连续工具结果合并为单个用户消息）
///
/// 对应 Kotlin AnthropicProvider.coalesceAnthropicMessages
fn coalesce_anthropic_messages(messages: &[ChatMessage]) -> Vec<Vec<ChatMessage>> {
    let mut groups: Vec<Vec<ChatMessage>> = Vec::new();
    let mut current_group: Vec<ChatMessage> = Vec::new();

    for msg in messages {
        let is_result = msg.id.starts_with("result_");
        let is_tool = msg.id.starts_with("tool_");

        if is_result {
            // 连续 result_ 消息合并到同一组
            current_group.push(msg.clone());
        } else {
            // 非 result_ 消息：先 flush 当前组
            if !current_group.is_empty() {
                groups.push(std::mem::take(&mut current_group));
            }
            if is_tool {
                // 单个 tool_ 消息单独一组
                groups.push(vec![msg.clone()]);
            } else {
                // 普通消息单独一组
                groups.push(vec![msg.clone()]);
            }
        }
    }

    // flush 最后剩余组
    if !current_group.is_empty() {
        groups.push(current_group);
    }

    groups
}

/// 将 tool_ 消息（助手工具调用请求）转换为 Anthropic assistant 消息
///
/// 对应 Kotlin AnthropicProvider.buildAssistantToolUse
fn convert_tool_message(msg: &ChatMessage) -> AnthropicApiMessage {
    let mut parts = Vec::new();

    // 如果有文本，添加 text 内容块
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

    // 如果有思考内容，添加 thinking 内容块（thinking replay 签名）
    if let Some(ref thoughts) = msg.thoughts {
        if !thoughts.is_empty() {
            // 从 tool_call_json 中提取签名
            let signature = msg.tool_call_json.as_ref().and_then(|tcj| {
                serde_json::from_str::<serde_json::Value>(tcj).ok().and_then(|v| {
                    v.get("signature")
                        .or_else(|| v.get("thought_signature"))
                        .and_then(|s| s.as_str())
                        .map(|s| s.to_string())
                })
            });

            parts.push(AnthropicContentBlock {
                block_type: "thinking".to_string(),
                text: None,
                source: None,
                id: None,
                name: None,
                input: None,
                thinking: Some(thoughts.clone()),
                signature,
                tool_use_id: None,
                content: None,
            });
        }
    }

    // 从 tool_call_json 解析工具调用
    if let Some(ref tcj) = msg.tool_call_json {
        if !tcj.is_empty() {
            if let Ok(tc) = serde_json::from_str::<serde_json::Value>(tcj) {
                // 尝试 segments 格式（多个工具调用）
                if let Some(segments) = tc.get("segments").and_then(|v| v.as_array()) {
                    for seg in segments {
                        if let Some(tool_use) = build_tool_use_block(seg) {
                            parts.push(tool_use);
                        }
                    }
                } else {
                    // 单个工具调用
                    if let Some(tool_use) = build_tool_use_block(&tc) {
                        parts.push(tool_use);
                    }
                }
            }
        }
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

    AnthropicApiMessage {
        role: "assistant".to_string(),
        content: parts,
    }
}

/// 从 JSON 值构建单个 tool_use 内容块
fn build_tool_use_block(tc: &serde_json::Value) -> Option<AnthropicContentBlock> {
    let tool_name = tc
        .get("toolName")
        .or_else(|| tc.get("tool_name"))
        .and_then(|v| v.as_str())?;
    let tool_call_id = tc
        .get("toolCallId")
        .or_else(|| tc.get("tool_call_id"))
        .and_then(|v| v.as_str())
        .unwrap_or("call_0");

    let arguments = tc
        .get("toolArgs")
        .or_else(|| tc.get("tool_args"))
        .or_else(|| tc.get("arguments"))
        .and_then(|v| {
            if let Some(s) = v.as_str() {
                serde_json::from_str(s).ok()
            } else {
                Some(v.clone())
            }
        })
        .unwrap_or_else(|| serde_json::Value::Object(serde_json::Map::new()));

    Some(AnthropicContentBlock {
        block_type: "tool_use".to_string(),
        text: None,
        source: None,
        id: Some(tool_call_id.to_string()),
        name: Some(tool_name.to_string()),
        input: Some(arguments),
        thinking: None,
        signature: None,
        tool_use_id: None,
        content: None,
    })
}

/// 将 result_ 消息（用户工具执行结果）转换为 Anthropic user 消息
///
/// 对应 Kotlin AnthropicProvider.buildToolResultBlocks
fn convert_result_message(msg: &ChatMessage) -> AnthropicApiMessage {
    let mut parts = Vec::new();

    if let Some(ref tcj) = msg.tool_call_json {
        if !tcj.is_empty() {
            if let Ok(tc) = serde_json::from_str::<serde_json::Value>(tcj) {
                let tool_name = tc
                    .get("toolName")
                    .or_else(|| tc.get("tool_name"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("unknown");
                let tool_call_id = tc
                    .get("toolCallId")
                    .or_else(|| tc.get("tool_call_id"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("call_0");
                let result = tc
                    .get("result")
                    .and_then(|v| v.as_str())
                    .unwrap_or("");

                // 如果有文本内容，添加为 tool_result 的 content
                let content_text = if !msg.text.trim().is_empty() {
                    Some(msg.text.clone())
                } else if !result.is_empty() {
                    Some(result.to_string())
                } else {
                    None
                };

                let mut tool_result_content = Vec::new();
                if let Some(ref text) = content_text {
                    tool_result_content.push(serde_json::json!({
                        "type": "text",
                        "text": text
                    }));
                }

                parts.push(AnthropicContentBlock {
                    block_type: "tool_result".to_string(),
                    text: None,
                    source: None,
                    id: None,
                    name: Some(tool_name.to_string()),
                    input: None,
                    thinking: None,
                    signature: None,
                    tool_use_id: Some(tool_call_id.to_string()),
                    content: if tool_result_content.is_empty() {
                        None
                    } else {
                        Some(serde_json::Value::Array(tool_result_content))
                    },
                });
            }
        }
    }

    // 如果没有 tool_call_json，回退到普通文本消息
    if parts.is_empty() {
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
        } else {
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
    }

    AnthropicApiMessage {
        role: "user".to_string(),
        content: parts,
    }
}

/// 将合并后的消息组转换为单个 Anthropic 消息
///
/// 一组可能包含多个 result_ 消息，需要合并为单个 user 消息
fn convert_message_group(group: &[ChatMessage], include_images: bool) -> AnthropicApiMessage {
    if group.len() == 1 {
        return convert_message(&group[0], include_images);
    }

    // 多个 result_ 消息合并为单个 user 消息，每个工具结果一个 tool_result 内容块
    let mut parts = Vec::new();

    for msg in group {
        if msg.id.starts_with("result_") {
            if let Some(ref tcj) = msg.tool_call_json {
                if !tcj.is_empty() {
                    if let Ok(tc) = serde_json::from_str::<serde_json::Value>(tcj) {
                        let tool_name = tc
                            .get("toolName")
                            .or_else(|| tc.get("tool_name"))
                            .and_then(|v| v.as_str())
                            .unwrap_or("unknown");
                        let tool_call_id = tc
                            .get("toolCallId")
                            .or_else(|| tc.get("tool_call_id"))
                            .and_then(|v| v.as_str())
                            .unwrap_or("call_0");
                        let result = tc
                            .get("result")
                            .and_then(|v| v.as_str())
                            .unwrap_or("");

                        let content_text = if !msg.text.trim().is_empty() {
                            Some(msg.text.clone())
                        } else {
                            Some(result.to_string())
                        };

                        let tool_result_content = vec![serde_json::json!({
                            "type": "text",
                            "text": content_text.unwrap_or_default()
                        })];

                        parts.push(AnthropicContentBlock {
                            block_type: "tool_result".to_string(),
                            text: None,
                            source: None,
                            id: None,
                            name: Some(tool_name.to_string()),
                            input: None,
                            thinking: None,
                            signature: None,
                            tool_use_id: Some(tool_call_id.to_string()),
                            content: Some(serde_json::Value::Array(tool_result_content)),
                        });
                    }
                }
            }
        }
    }

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

    AnthropicApiMessage {
        role: "user".to_string(),
        content: parts,
    }
}

/// 将 ChatMessage 转换为 Anthropic 格式的 API 消息
///
/// 根据消息 ID 前缀区分：
/// - `tool_` → assistant 角色 + tool_use 内容块
/// - `result_` → user 角色 + tool_result 内容块
/// - 其他 → 根据 participant 确定角色
fn convert_message(msg: &ChatMessage, include_images: bool) -> AnthropicApiMessage {
    // tool_ 消息：助手发出的工具调用请求
    if msg.id.starts_with("tool_") {
        return convert_tool_message(msg);
    }

    // result_ 消息：用户的工具执行结果
    if msg.id.starts_with("result_") {
        return convert_result_message(msg);
    }
    let mut parts = Vec::new();

    // 图像（仅用户消息）
    if include_images && matches!(msg.participant, Participant::User) {
        for image_path in &msg.images {
            if image_path.is_empty() {
                continue;
            }
            if image_path.starts_with("data:") {
                // data URL: 解析 MIME 和 base64
                if let Some(rest) = image_path.strip_prefix("data:") {
                    if let Some(semi) = rest.find(';') {
                        let mime_type = &rest[..semi];
                        if let Some(b64) = rest.find("base64,") {
                            let data = rest[b64 + 7..].to_string();
                            parts.push(AnthropicContentBlock {
                                block_type: "image".to_string(),
                                source: Some(AnthropicImageSource {
                                    source_type: "base64".to_string(),
                                    media_type: mime_type.to_string(),
                                    data,
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
            } else if let Some((mime_type, base64_data)) = encode_image_to_base64(image_path) {
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

    // 思考内容（assistant 消息的 thinking replay，用于多轮对话保持思考上下文）
    if let Some(ref thoughts) = msg.thoughts {
        if !thoughts.is_empty() && msg.participant != Participant::User {
            let signature = msg.tool_call_json.as_ref().and_then(|tcj| {
                serde_json::from_str::<serde_json::Value>(tcj).ok().and_then(|v| {
                    v.get("signature")
                        .or_else(|| v.get("thought_signature"))
                        .and_then(|s| s.as_str())
                        .map(|s| s.to_string())
                })
            });
            // 仅在有签名时添加 thinking 块（Anthropic 要求签名）
            if let Some(sig) = signature {
                parts.push(AnthropicContentBlock {
                    block_type: "thinking".to_string(),
                    text: None,
                    source: None,
                    id: None,
                    name: None,
                    input: None,
                    thinking: Some(thoughts.clone()),
                    signature: Some(sig),
                    tool_use_id: None,
                    content: None,
                });
            }
        }
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

/// 将 ToolProperty 递归转换为 JSON Schema 对象
fn tool_property_to_schema(prop: &ToolProperty) -> serde_json::Value {
    let mut schema = serde_json::Map::new();
    schema.insert("type".to_string(), serde_json::Value::String(prop.r#type.clone()));
    schema.insert("description".to_string(), serde_json::Value::String(prop.description.clone()));
    if let Some(ref items) = prop.items {
        schema.insert("items".to_string(), tool_property_to_schema(items));
    }
    serde_json::Value::Object(schema)
}

/// 将 ToolDefinition 转换为 Anthropic 格式
fn convert_tools(tools: &[ToolDefinition]) -> Vec<AnthropicTool> {
    tools
        .iter()
        .map(|td| {
            let params = &td.function.parameters;
            let mut properties_map = serde_json::Map::new();
            for (name, prop) in &params.properties {
                properties_map.insert(name.clone(), tool_property_to_schema(prop));
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

    /// 解析 SSE 事件流，通过回调实时推送事件
    async fn parse_sse_stream(
        stream_response: &mut crate::api::http_client::StreamResponse,
        on_event: &StreamCallback,
    ) -> Result<(), AgoraError> {
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
                        let mut data_json: Option<String> = None;

                        for line in block.lines() {
                            if let Some(val) = strip_sse_field(line, "data") {
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
                                                    on_event(StreamEvent::ThoughtChunk {
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
                                                    on_event(StreamEvent::TextChunk {
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
                                                    on_event(StreamEvent::ThoughtChunk {
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
                                    if let (Some(id), Some(name)) =
                                        (&tool_use_id, &tool_use_name)
                                    {
                                        on_event(StreamEvent::ToolCallRequest {
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
                                            on_event(StreamEvent::UsageUpdate {
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
                                        on_event(StreamEvent::Error {
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

        Ok(())
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
        on_event: &StreamCallback,
    ) -> Result<(), AgoraError> {
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

        // ── 消息准备管道（对应 Kotlin prepareMessages）──
        let prepared_messages = message_pipeline::prepare_messages(messages, config.max_context_window);

        // ── 助手图像投影到最新用户消息
        let prepared_messages = message_pipeline::project_assistant_images_to_latest_user_message(
            &prepared_messages,
            config.include_images,
        );

        // ── 工具定义验证（对应 Kotlin validateToolDefinitions）──
        if let Some(ref tools) = config.tools {
            let violations = request_validator::validate_tool_definitions(tools);
            if !violations.is_empty() {
                on_event(StreamEvent::Error {
                    error: GenerationError::RequestFormat {
                        provider: self.name.clone(),
                        violations,
                    },
                });
                return Ok(());
            }
        }

        // ── 过滤不兼容的工具消息（对应 Kotlin isAnthropicToolRoundCompatible）──
        // 当 thinking 启用时，带有思考内容的工具消息必须携带有效签名。
        let signed_thinking_required = thinking_config.is_some();
        let filtered_messages: Vec<ChatMessage> = if signed_thinking_required {
            prepared_messages
                .iter()
                .filter(|msg| {
                    if msg.id.starts_with("tool_") {
                        is_anthropic_tool_round_compatible(msg)
                    } else {
                        true
                    }
                })
                .cloned()
                .collect()
        } else {
            prepared_messages
        };

        // ── Anthropic 消息合并（coalesce）──
        let message_groups = coalesce_anthropic_messages(&filtered_messages);

        // ── 消息转换（使用消息组）──
        let api_messages: Vec<AnthropicApiMessage> = message_groups
            .iter()
            .map(|group| {
                if group.len() > 1 {
                    convert_message_group(group, config.include_images)
                } else {
                    convert_message(&group[0], config.include_images)
                }
            })
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
        let request_body_str = serde_json::to_string(&request_body)?;

        // ── 序列化请求验证（对应 Kotlin requireValidSerializedRequest）──
        if let Err(e) = request_validator::require_valid_serialized_request(
            &self.name,
            &request_body_str,
            &["model"],
            &["messages"],
        ) {
            on_event(StreamEvent::Error {
                error: GenerationError::RequestFormat {
                    provider: self.name.clone(),
                    violations: e.violations,
                },
            });
            return Ok(());
        }

        let url = format!("{}/messages", base_url);
        let headers = Self::build_headers(&config.api_key);

        // ── 重试循环 ──
        let max_attempts = 3;
        let mut last_error: Option<AgoraError> = None;

        for attempt in 1..=max_attempts {
            let mut stream_response = match client.stream_post(&url, &json_body, &headers).await {
                Ok(resp) => resp,
                Err(e) => {
                    last_error = Some(e);
                    break;
                }
            };

            // 如果 HTTP 状态码是 200，解析流（实时回调）
            if stream_response.code == 200 {
                return Self::parse_sse_stream(&mut stream_response, on_event).await;
            }

            // 可重试状态码
            let retryable = matches!(stream_response.code, 429 | 502 | 503 | 504);
            if retryable && attempt < max_attempts {
                on_event(StreamEvent::Retrying {
                    attempt: attempt as i32,
                    max_attempts: max_attempts as i32,
                });
                tokio::time::sleep(std::time::Duration::from_millis(1000 * attempt as u64)).await;
                continue;
            }

            // 不可重试的错误
            let error_body = stream_response.error_body.unwrap_or_default();
            last_error = Some(match stream_response.code {
                401 => AgoraError::Api {
                    code: "401".to_string(),
                    message: format!("Authentication failed: {}. Check your API key.", error_body),
                    error_type: None,
                },
                403 => AgoraError::Api {
                    code: "403".to_string(),
                    message: format!("Access forbidden: {}", error_body),
                    error_type: None,
                },
                404 => AgoraError::Api {
                    code: "404".to_string(),
                    message: format!("Not found: {}", error_body),
                    error_type: None,
                },
                _ => AgoraError::Network {
                    status_code: stream_response.code as i32,
                    message: format!("HTTP {}: {}", stream_response.code, error_body),
                },
            });
            break;
        }

        Err(last_error
            .unwrap_or_else(|| AgoraError::Unknown("Unknown error during generation".to_string())))
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