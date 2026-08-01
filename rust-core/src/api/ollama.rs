// Ollama Provider 实现
//
// 对接 Ollama 本地 API，支持流式对话、思考标签解析、工具调用、图像处理。
// 参考 Kotlin OllamaProvider 的实现模式。

use std::collections::HashMap;

use async_trait::async_trait;
use futures::StreamExt;

use crate::api::http_client::AgoraHttpClient;
use crate::api::message_pipeline;
use crate::api::provider::LlmProvider;
use crate::api::request_validator;
use crate::api::types::*;
use crate::error::AgoraError;

// ============================================================
// 常量
// ============================================================

const TOOL_MSG_PREFIX: &str = "tool_";
const RESULT_MSG_PREFIX: &str = "result_";
const THINK_START: &str = " 思考";
const THINK_END: &str = "";

// ============================================================
// Ollama 请求消息（扩展字段用于 tool 和 thinking）
// ============================================================

#[derive(Debug, Clone, serde::Serialize)]
struct OllamaRequestMessage {
    pub role: String,
    pub content: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub images: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<serde_json::Value>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thinking: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", rename = "tool_name")]
    pub tool_name: Option<String>,
}

// ============================================================
// OllamaProvider — 主结构体
// ============================================================

/// Ollama 本地 API 提供商
pub struct OllamaProvider {
    name: String,
    default_base_url: String,
}

impl OllamaProvider {
    pub fn new() -> Self {
        Self {
            name: "ollama".to_string(),
            default_base_url: "http://localhost:11434/api".to_string(),
        }
    }

    /// 解析 base_url：优先使用 config.base_url，否则使用默认值
    fn effective_base_url(&self, config: &ProviderConfig) -> String {
        config
            .base_url
            .as_deref()
            .map(|s| s.trim_end_matches('/').to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| self.default_base_url.clone())
    }
}

impl Default for OllamaProvider {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl LlmProvider for OllamaProvider {
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
        let base_url = self.effective_base_url(config);
        let model_name = config.model_id.clone();

        // 消息准备管道 + 构建 Ollama 消息列表
        let prepared_messages = message_pipeline::prepare_messages(messages, config.max_context_window);
        let api_messages = build_ollama_messages(&prepared_messages, config)?;

        // 构建 options
        let options = build_ollama_options(config);

        // 构建 tools
        let tools = config.tools.as_ref().map(|tds| {
            tds.iter()
                .filter_map(|t| serde_json::to_value(t).ok())
                .collect::<Vec<_>>()
        });

        // ── 工具定义验证（对应 Kotlin validateToolDefinitions）──
        if let Some(ref tools) = config.tools {
            let violations = request_validator::validate_tool_definitions(tools);
            if !violations.is_empty() {
                return Ok(vec![StreamEvent::Error {
                    error: GenerationError::RequestFormat {
                        provider: self.name.clone(),
                        violations,
                    },
                }]);
            }
        }

        let request = serde_json::json!({
            "model": model_name,
            "messages": api_messages,
            "stream": true,
            "options": options,
            "tools": tools,
        });

        let request_body_str = serde_json::to_string(&request)?;

        // ── 序列化请求验证（对应 Kotlin requireValidSerializedRequest）──
        if let Err(e) = request_validator::require_valid_serialized_request(
            &self.name,
            &request_body_str,
            &["model"],
            &["messages"],
        ) {
            return Ok(vec![StreamEvent::Error {
                error: GenerationError::RequestFormat {
                    provider: self.name.clone(),
                    violations: e.violations,
                },
            }]);
        }

        let url = format!("{}/chat", base_url);
        let mut headers = HashMap::new();
        headers.insert("Content-Type".to_string(), "application/json".to_string());
        if !config.api_key.is_empty() {
            headers.insert(
                "Authorization".to_string(),
                format!("Bearer {}", config.api_key),
            );
        }

        // ── 重试循环 ──
        let max_attempts = 3;
        let mut last_error: Option<AgoraError> = None;
        let mut retry_events: Vec<StreamEvent> = Vec::new();

        for attempt in 1..=max_attempts {
            let mut stream_resp = match client.stream_post(&url, &request, &headers).await {
                Ok(resp) => resp,
                Err(e) => {
                    last_error = Some(e);
                    break;
                }
            };

            // 如果 HTTP 状态码是 200，解析流
            if stream_resp.code == 200 {
                match parse_ollama_stream(stream_resp.stream(), config.thinking_enabled).await {
                    Ok(mut events) => {
                        if !retry_events.is_empty() {
                            let mut all = std::mem::take(&mut retry_events);
                            all.append(&mut events);
                            return Ok(all);
                        }
                        return Ok(events);
                    }
                    Err(e) => {
                        last_error = Some(e);
                    }
                }
                break;
            }

            // 收集错误体
            let mut error_body = String::new();
            let stream = stream_resp.stream();
            while let Some(chunk) = stream.next().await {
                if let Ok(bytes) = chunk {
                    error_body.push_str(&String::from_utf8_lossy(&bytes));
                }
            }

            // 可重试状态码
            let retryable = matches!(stream_resp.code, 429 | 502 | 503 | 504);
            if retryable && attempt < max_attempts {
                retry_events.push(StreamEvent::Retrying {
                    attempt: attempt as i32,
                    max_attempts: max_attempts as i32,
                });
                tokio::time::sleep(std::time::Duration::from_millis(1000 * attempt as u64)).await;
                continue;
            }

            // 不可重试的错误
            last_error = Some(match stream_resp.code {
                401 => AgoraError::Api {
                    code: "401".to_string(),
                    message: format!("Authentication failed: {}. Check your API key.", error_body),
                },
                _ => AgoraError::Network {
                    status_code: stream_resp.code as i32,
                    message: error_body,
                },
            });
            break;
        }

        // 如果有重试事件但最终还是失败，将重试事件和错误一起返回
        if !retry_events.is_empty() {
            let mut all = retry_events;
            all.push(StreamEvent::Error {
                error: GenerationError::Network {
                    status_code: last_error.as_ref().and_then(|e| e.status_code()).unwrap_or(0),
                    message: last_error.as_ref().map(|e| e.to_string()).unwrap_or_else(|| "Unknown error".to_string()),
                },
            });
            return Ok(all);
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
        let effective_url = base_url
            .map(|s| s.trim_end_matches('/').to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| self.default_base_url.clone());

        let url = format!("{}/tags", effective_url);
        let headers = HashMap::new();

        let body = client.get(&url, &headers).await?;
        let response: OllamaModelListResponse = serde_json::from_str(&body)?;

        Ok(response.models.into_iter().map(|m| m.name).collect())
    }
}

// ============================================================
// 消息构建
// ============================================================

fn build_ollama_messages(
    messages: &[ChatMessage],
    config: &ProviderConfig,
) -> Result<Vec<OllamaRequestMessage>, AgoraError> {
    let mut api_messages: Vec<OllamaRequestMessage> = Vec::new();

    // 添加系统提示
    if let Some(ref system_prompt) = config.system_prompt {
        if !system_prompt.is_empty() {
            api_messages.push(OllamaRequestMessage {
                role: "system".to_string(),
                content: system_prompt.clone(),
                images: None,
                tool_calls: None,
                thinking: None,
                tool_name: None,
            });
        }
    }

    // 添加 user prepend
    if let Some(ref prepend) = config.user_prepend {
        if !prepend.is_empty() {
            api_messages.push(OllamaRequestMessage {
                role: "system".to_string(),
                content: prepend.clone(),
                images: None,
                tool_calls: None,
                thinking: None,
                tool_name: None,
            });
        }
    }

    for msg in messages {
        // 处理 tool 消息（模型发出的工具调用请求）
        if msg.id.starts_with(TOOL_MSG_PREFIX) {
            let tool_calls = parse_tool_call_json(&msg.tool_call_json);
            let thinking = msg.thoughts.clone().filter(|t| !t.is_empty());
            api_messages.push(OllamaRequestMessage {
                role: "assistant".to_string(),
                content: String::new(),
                images: None,
                tool_calls,
                thinking,
                tool_name: None,
            });
            continue;
        }

        // 处理 result 消息（工具执行结果）
        if msg.id.starts_with(RESULT_MSG_PREFIX) {
            let (tool_name, result) = parse_tool_result_json(&msg.tool_call_json);
            api_messages.push(OllamaRequestMessage {
                role: "tool".to_string(),
                content: result,
                images: None,
                tool_calls: None,
                thinking: None,
                tool_name,
            });
            continue;
        }

        // 普通消息
        let role = match msg.participant {
            Participant::User => "user",
            Participant::Model => "assistant",
            Participant::Error => "assistant",
        };

        let content = if msg.participant == Participant::User {
            let mut text = msg.text.clone();
            if let Some(ref postpend) = config.user_postpend {
                if !postpend.is_empty() {
                    text.push_str(postpend);
                }
            }
            text
        } else {
            msg.text.clone()
        };

        let images = if config.include_images && msg.participant == Participant::User {
            let encoded: Vec<String> = msg
                .images
                .iter()
                .filter_map(|path| {
                    std::fs::read(path)
                        .ok()
                        .map(|bytes| base64::Engine::encode(&base64::engine::general_purpose::STANDARD, &bytes))
                })
                .collect();
            if encoded.is_empty() {
                None
            } else {
                Some(encoded)
            }
        } else {
            None
        };

        api_messages.push(OllamaRequestMessage {
            role: role.to_string(),
            content,
            images,
            tool_calls: None,
            thinking: None,
            tool_name: None,
        });
    }

    Ok(api_messages)
}

// ============================================================
// 选项构建
// ============================================================

fn build_ollama_options(config: &ProviderConfig) -> serde_json::Value {
    let mut map = serde_json::Map::new();

    if let Some(temperature) = config.temperature {
        map.insert(
            "temperature".to_string(),
            serde_json::json!(temperature),
        );
    }
    if let Some(top_p) = config.top_p {
        map.insert("top_p".to_string(), serde_json::json!(top_p));
    }
    if let Some(max_tokens) = config.max_tokens {
        if max_tokens > 0 {
            map.insert("num_predict".to_string(), serde_json::json!(max_tokens));
        }
    }
    if let Some(freq_penalty) = config.frequency_penalty {
        map.insert(
            "frequency_penalty".to_string(),
            serde_json::json!(freq_penalty),
        );
    }
    if let Some(pres_penalty) = config.presence_penalty {
        map.insert(
            "presence_penalty".to_string(),
            serde_json::json!(pres_penalty),
        );
    }

    serde_json::Value::Object(map)
}

// ============================================================
// 工具调用 JSON 解析
// ============================================================

/// 从 tool_call_json 中解析工具调用列表
fn parse_tool_call_json(json_str: &Option<String>) -> Option<Vec<serde_json::Value>> {
    let json_str = json_str.as_ref()?;
    if json_str.is_empty() {
        return None;
    }

    let tool_info: serde_json::Value = serde_json::from_str(json_str).ok()?;

    // 尝试 segments 格式
    if let Some(segments) = tool_info.get("segments").and_then(|v| v.as_array()) {
        let calls: Vec<serde_json::Value> = segments
            .iter()
            .filter(|seg| {
                seg.get("type")
                    .and_then(|v| v.as_str())
                    .map(|t| t == "tool")
                    .unwrap_or(false)
            })
            .filter_map(|seg| {
                let tool_name = seg
                    .get("toolName")
                    .or_else(|| seg.get("tool_name"))
                    .and_then(|v| v.as_str())?;
                let tool_call_id = seg
                    .get("toolCallId")
                    .or_else(|| seg.get("tool_call_id"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("call_0");
                let tool_args = seg
                    .get("toolArgs")
                    .or_else(|| seg.get("tool_args"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("{}");

                let args_value: serde_json::Value =
                    serde_json::from_str(tool_args).unwrap_or(serde_json::Value::String(
                        tool_args.to_string(),
                    ));

                Some(serde_json::json!({
                    "id": tool_call_id,
                    "type": "function",
                    "function": {
                        "name": tool_name,
                        "arguments": args_value
                    }
                }))
            })
            .collect();

        if calls.is_empty() {
            None
        } else {
            Some(calls)
        }
    } else {
        // 单个工具调用格式
        let tool_name = tool_info
            .get("tool_name")
            .or_else(|| tool_info.get("toolName"))
            .and_then(|v| v.as_str())?;
        let tool_call_id = tool_info
            .get("tool_call_id")
            .or_else(|| tool_info.get("toolCallId"))
            .and_then(|v| v.as_str())
            .unwrap_or("call_0");
        let arguments = tool_info
            .get("arguments")
            .or_else(|| tool_info.get("args"))
            .and_then(|v| v.as_str())
            .unwrap_or("{}");

        let args_value: serde_json::Value =
            serde_json::from_str(arguments).unwrap_or(serde_json::Value::String(
                arguments.to_string(),
            ));

        Some(vec![serde_json::json!({
            "id": tool_call_id,
            "type": "function",
            "function": {
                "name": tool_name,
                "arguments": args_value
            }
        })])
    }
}

/// 从 tool_call_json 中解析工具返回结果
fn parse_tool_result_json(json_str: &Option<String>) -> (Option<String>, String) {
    let json_str = match json_str.as_ref() {
        Some(s) if !s.is_empty() => s,
        _ => return (None, String::new()),
    };

    let tool_info: serde_json::Value = match serde_json::from_str(json_str) {
        Ok(v) => v,
        Err(_) => return (None, String::new()),
    };

    let tool_name = tool_info
        .get("tool_name")
        .or_else(|| tool_info.get("toolName"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());

    let result = tool_info
        .get("result")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    (tool_name, result)
}

// ============================================================
// 流式解析
// ============================================================

/// 解析 Ollama 流式响应为 StreamEvent 列表
async fn parse_ollama_stream(
    stream: &mut (dyn futures::Stream<Item = Result<bytes::Bytes, reqwest::Error>> + Send + Unpin),
    thinking_enabled: bool,
) -> Result<Vec<StreamEvent>, AgoraError> {
    let mut events: Vec<StreamEvent> = Vec::new();
    let mut buffer = String::new();
    let mut received_structured_thinking = false;
    let mut think_parser = ThinkTagStateMachine::new();

    while let Some(chunk) = stream.next().await {
        let bytes = match chunk {
            Ok(b) => b,
            Err(e) => {
                events.push(StreamEvent::Error {
                    error: GenerationError::Network {
                        status_code: 0,
                        message: format!("Stream read error: {}", e),
                    },
                });
                break;
            }
        };

        buffer.push_str(&String::from_utf8_lossy(&bytes));

        // 处理完整的行（Ollama 每行一个完整的 JSON 对象）
        while let Some(pos) = buffer.find('\n') {
            let line = buffer[..pos].trim().to_string();
            buffer.drain(..pos + 1);

            if line.is_empty() {
                continue;
            }

            match serde_json::from_str::<serde_json::Value>(&line) {
                Ok(response) => {
                    // 处理 message 字段
                    if let Some(msg) = response.get("message") {
                        // 1. 处理显式 thinking 字段（Ollama 0.5.4+）
                        if let Some(thinking) = msg.get("thinking").and_then(|v| v.as_str()) {
                            if !thinking.is_empty() && thinking_enabled {
                                events.push(StreamEvent::ThoughtChunk {
                                    thought: thinking.to_string(),
                                    title: None,
                                    signature: None,
                                });
                                received_structured_thinking = true;
                            }
                        }

                        // 2. 处理 tool_calls
                        if let Some(tool_calls) = msg.get("tool_calls").and_then(|v| v.as_array()) {
                            let calls: Vec<ToolCallRequestData> = tool_calls
                                .iter()
                                .filter_map(|tc| {
                                    let id = tc
                                        .get("id")
                                        .and_then(|v| v.as_str())
                                        .unwrap_or("call_0");
                                    let function = tc.get("function")?;
                                    let name = function
                                        .get("name")
                                        .and_then(|v| v.as_str())
                                        .unwrap_or("");
                                    let args = function
                                        .get("arguments")
                                        .map(|v| {
                                            if let Some(s) = v.as_str() {
                                                s.to_string()
                                            } else {
                                                v.to_string()
                                            }
                                        })
                                        .unwrap_or_default();

                                    if name.is_empty() {
                                        return None;
                                    }
                                    Some(ToolCallRequestData {
                                        id: id.to_string(),
                                        name: name.to_string(),
                                        arguments: args,
                                        signature: None,
                                    })
                                })
                                .collect();

                            if calls.len() == 1 {
                                let c = &calls[0];
                                events.push(StreamEvent::ToolCallRequest {
                                    id: c.id.clone(),
                                    name: c.name.clone(),
                                    arguments: c.arguments.clone(),
                                    signature: c.signature.clone(),
                                });
                            } else if calls.len() > 1 {
                                events.push(StreamEvent::ToolCallsRequest { calls });
                            }
                        }

                        // 3. 处理 content（文本 + 思考标签）
                        if let Some(content) = msg.get("content").and_then(|v| v.as_str()) {
                            if !content.is_empty() {
                                if received_structured_thinking {
                                    // 已收到结构化 thinking，content 直接作为文本
                                    events.push(StreamEvent::TextChunk {
                                        text: content.to_string(),
                                    });
                                } else {
                                    // 使用状态机解析 思考 标签
                                    think_parser.feed(content, thinking_enabled, &mut events);
                                }
                            }
                        }
                    }

                    // 处理 done 标志
                    if response.get("done").and_then(|v| v.as_bool()).unwrap_or(false) {
                        let prompt_count = response
                            .get("prompt_eval_count")
                            .and_then(|v| v.as_i64())
                            .unwrap_or(0) as i32;
                        let eval_count = response
                            .get("eval_count")
                            .and_then(|v| v.as_i64())
                            .unwrap_or(0) as i32;
                        events.push(StreamEvent::UsageUpdate {
                            token_count: prompt_count + eval_count,
                            thoughts_token_count: 0,
                        });
                    }

                    // 处理错误
                    if let Some(error_msg) = response.get("error").and_then(|v| v.as_str()) {
                        events.push(StreamEvent::Error {
                            error: GenerationError::Api {
                                code: "ollama_error".to_string(),
                                message: error_msg.to_string(),
                                error_type: None,
                            },
                        });
                    }
                }
                Err(e) => {
                    log::warn!("Ollama parse error: {} - line: {}", e, line);
                }
            }
        }
    }

    // 最终 flush think parser
    think_parser.flush(thinking_enabled, &mut events);

    Ok(events)
}

// ============================================================
// ThinkTagStateMachine — 思考标签状态机
// ============================================================

/// 流式 思考 标签解析器
///
/// 处理 Ollama 旧版模型中嵌入在 content 字段中的思考标签。
/// 支持跨 chunk 的部分标签匹配。
struct ThinkTagStateMachine {
    in_think: bool,
    buffer: String,
}

impl ThinkTagStateMachine {
    fn new() -> Self {
        Self {
            in_think: false,
            buffer: String::new(),
        }
    }

    fn feed(&mut self, content: &str, thinking_enabled: bool, events: &mut Vec<StreamEvent>) {
        self.buffer.push_str(content);
        self.drain(thinking_enabled, events);
    }

    fn flush(&mut self, thinking_enabled: bool, events: &mut Vec<StreamEvent>) {
        if !self.buffer.is_empty() {
            if self.in_think && thinking_enabled {
                events.push(StreamEvent::ThoughtChunk {
                    thought: std::mem::take(&mut self.buffer),
                    title: None,
                    signature: None,
                });
            } else if !self.in_think {
                events.push(StreamEvent::TextChunk {
                    text: std::mem::take(&mut self.buffer),
                });
            } else {
                // 在 thinking 中但 thinking 被禁用，丢弃
                self.buffer.clear();
            }
        }
    }

    fn drain(&mut self, thinking_enabled: bool, events: &mut Vec<StreamEvent>) {
        loop {
            if self.in_think {
                // 在思考块内，寻找结束标签
                if let Some(pos) = self.buffer.find(THINK_END) {
                    let thought = self.buffer[..pos].to_string();
                    if thinking_enabled && !thought.is_empty() {
                        events.push(StreamEvent::ThoughtChunk {
                            thought,
                            title: None,
                            signature: None,
                        });
                    }
                    self.buffer.drain(..pos + THINK_END.len());
                    self.in_think = false;
                } else {
                    // 检查是否以结束标签的前缀结尾（跨 chunk 等待）
                    if is_partial_match(&self.buffer, THINK_END) {
                        break;
                    }
                    // 没有结束标签，保持所有内容在缓冲区中
                    break;
                }
            } else {
                // 不在思考块内，寻找开始标签
                if let Some(pos) = self.buffer.find(THINK_START) {
                    // 发射开始标签之前的文本
                    if pos > 0 {
                        events.push(StreamEvent::TextChunk {
                            text: self.buffer[..pos].to_string(),
                        });
                    }
                    self.buffer.drain(..pos + THINK_START.len());
                    self.in_think = true;
                } else {
                    // 检查是否以开始标签的前缀结尾（跨 chunk 等待）
                    if is_partial_match(&self.buffer, THINK_START) {
                        // 保留可能的部分标签前缀
                        let prefix_len = longest_partial_prefix(&self.buffer, THINK_START);
                        if prefix_len > 0 {
                            let split = self.buffer.len() - prefix_len;
                            if split > 0 {
                                events.push(StreamEvent::TextChunk {
                                    text: self.buffer[..split].to_string(),
                                });
                                self.buffer.drain(..split);
                            }
                        }
                        break;
                    }
                    // 没有开始标签，发射所有文本
                    if !self.buffer.is_empty() {
                        events.push(StreamEvent::TextChunk {
                            text: std::mem::take(&mut self.buffer),
                        });
                    }
                    break;
                }
            }
        }
    }
}

/// 检查 buffer 是否以 target 的部分前缀结尾
fn is_partial_match(buffer: &str, target: &str) -> bool {
    for len in 1..target.len() {
        let prefix = &target[..len];
        if buffer.ends_with(prefix) {
            return true;
        }
    }
    false
}

/// 获取 buffer 结尾与 target 匹配的最长前缀长度
fn longest_partial_prefix(buffer: &str, target: &str) -> usize {
    for len in (1..target.len()).rev() {
        let prefix = &target[..len];
        if buffer.ends_with(prefix) {
            return len;
        }
    }
    0
}

// ============================================================
// 测试
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_think_parser_basic() {
        let mut events = Vec::new();
        let mut parser = ThinkTagStateMachine::new();
        parser.feed("Hello 思考this is thinking world", true, &mut events);
        parser.flush(true, &mut events);

        assert_eq!(events.len(), 2);
        match &events[0] {
            StreamEvent::TextChunk { text } => assert_eq!(text, "Hello "),
            _ => panic!("Expected TextChunk"),
        }
        match &events[1] {
            StreamEvent::TextChunk { text } => assert_eq!(text, " world"),
            _ => panic!("Expected TextChunk"),
        }
        // 思考内容应该在中间
        let has_thought = events.iter().any(|e| matches!(e, StreamEvent::ThoughtChunk { thought, .. } if thought == "this is thinking"));
        assert!(has_thought);
    }

    #[test]
    fn test_think_parser_disabled() {
        let mut events = Vec::new();
        let mut parser = ThinkTagStateMachine::new();
        parser.feed("Hello 思考hidden world", false, &mut events);
        parser.flush(false, &mut events);

        // 思考被禁用，不应有 ThoughtChunk
        let has_thought = events.iter().any(|e| matches!(e, StreamEvent::ThoughtChunk { .. }));
        assert!(!has_thought);
    }

    #[test]
    fn test_think_parser_split_across_chunks() {
        let mut events = Vec::new();
        let mut parser = ThinkTagStateMachine::new();

        parser.feed("Hello <thi", true, &mut events);
        // 此时只有 "Hello " 被发射
        assert_eq!(events.len(), 1);
        if let StreamEvent::TextChunk { text } = &events[0] {
            assert_eq!(text, "Hello ");
        }

        events.clear();
        parser.feed("nk>thinking</t", true, &mut events);
        // 等待结束标签
        assert!(events.is_empty());

        events.clear();
        parser.feed("hink> world", true, &mut events);
        parser.flush(true, &mut events);

        let has_thought = events.iter().any(|e| matches!(e, StreamEvent::ThoughtChunk { thought, .. } if thought == "thinking"));
        assert!(has_thought);
        let has_text = events
            .iter()
            .any(|e| matches!(e, StreamEvent::TextChunk { text } if text == " world"));
        assert!(has_text);
    }

    #[test]
    fn test_think_parser_no_tags() {
        let mut events = Vec::new();
        let mut parser = ThinkTagStateMachine::new();
        parser.feed("Hello, world!", true, &mut events);
        parser.flush(true, &mut events);

        assert_eq!(events.len(), 1);
        match &events[0] {
            StreamEvent::TextChunk { text } => assert_eq!(text, "Hello, world!"),
            _ => panic!("Expected TextChunk"),
        }
    }

    #[test]
    fn test_think_parser_empty() {
        let mut events = Vec::new();
        let mut parser = ThinkTagStateMachine::new();
        parser.feed("", true, &mut events);
        parser.flush(true, &mut events);
        assert!(events.is_empty());
    }

    #[test]
    fn test_think_parser_multiple_blocks() {
        let mut events = Vec::new();
        let mut parser = ThinkTagStateMachine::new();
        parser.feed("A 思考t1B 思考t2C", true, &mut events);
        parser.flush(true, &mut events);

        let thoughts: Vec<_> = events
            .iter()
            .filter_map(|e| {
                if let StreamEvent::ThoughtChunk { thought, .. } = e {
                    Some(thought.as_str())
                } else {
                    None
                }
            })
            .collect();
        assert_eq!(thoughts, vec!["t1", "t2"]);
    }

    #[test]
    fn test_parse_tool_call_json_segments() {
        let json = r#"{"segments":[{"type":"tool","toolName":"search","toolCallId":"call_1","toolArgs":"{\"query\":\"test\"}"}]}"#;
        let result = parse_tool_call_json(&Some(json.to_string()));
        assert!(result.is_some());
        let calls = result.unwrap();
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0]["function"]["name"], "search");
    }

    #[test]
    fn test_parse_tool_call_json_single() {
        let json = r#"{"tool_name":"calc","tool_call_id":"abc","arguments":"{\"expr\":\"1+1\"}"}"#;
        let result = parse_tool_call_json(&Some(json.to_string()));
        assert!(result.is_some());
        let calls = result.unwrap();
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0]["function"]["name"], "calc");
    }

    #[test]
    fn test_parse_tool_result_json() {
        let json = r#"{"tool_name":"search","result":"found 3 items"}"#;
        let (name, result) = parse_tool_result_json(&Some(json.to_string()));
        assert_eq!(name, Some("search".to_string()));
        assert_eq!(result, "found 3 items");
    }

    #[test]
    fn test_parse_tool_result_json_empty() {
        let (name, result) = parse_tool_result_json(&None);
        assert!(name.is_none());
        assert!(result.is_empty());
    }

    #[test]
    fn test_build_ollama_options() {
        let config = ProviderConfig {
            api_key: String::new(),
            model_id: "llama3".to_string(),
            system_prompt: None,
            max_context_window: 20,
            code_execution_enabled: false,
            google_search_enabled: false,
            thinking_enabled: true,
            thinking_level: "medium".to_string(),
            thinking_budget_enabled: false,
            thinking_budget_tokens: 4096,
            base_url: None,
            tools: None,
            user_prepend: None,
            user_postpend: None,
            include_images: true,
            temperature: Some(0.7),
            max_tokens: Some(2048),
            top_p: Some(0.9),
            frequency_penalty: None,
            presence_penalty: None,
        };

        let options = build_ollama_options(&config);
        assert_eq!(options["temperature"], 0.7);
        assert_eq!(options["top_p"], 0.9);
        assert_eq!(options["num_predict"], 2048);
    }

    #[test]
    fn test_is_partial_match() {
        assert!(is_partial_match("hello <thi", " 思考"));
        assert!(!is_partial_match("hello world", " 思考"));
        assert!(is_partial_match("text ", ""));
        assert!(!is_partial_match("", " 思考"));
    }
}