// OpenAI 兼容 Provider 实现
//
// 支持 OpenAI、DeepSeek、Groq、Qwen、OpenRouter 及任意 OpenAI 兼容端点。
// 参考 Kotlin BaseOpenAiProvider 的实现模式。

use std::collections::HashMap;

use async_trait::async_trait;
use futures::StreamExt;

use crate::api::http_client::AgoraHttpClient;
use crate::api::message_pipeline;
use crate::api::provider::LlmProvider;
use crate::api::request_validator;
use crate::api::sse::{strip_sse_field, parse_sse_json_stream, SseEvent};
use crate::api::types::*;
use crate::error::AgoraError;

// ============================================================
// 常量
// ============================================================

const TOOL_MSG_PREFIX: &str = "tool_";
const RESULT_MSG_PREFIX: &str = "result_";

// ============================================================
// OpenAiProvider — 主结构体
// ============================================================

/// OpenAI 兼容 API 提供商
///
/// 通过不同构造函数支持多个提供商：
/// - `new_openai()` — OpenAI
/// - `new_deepseek()` — DeepSeek
/// - `new_groq()` — Groq
/// - `new_qwen()` — Qwen (通义千问)
/// - `new_openrouter()` — OpenRouter
/// - `new_custom(name, base_url)` — 自定义 OpenAI 兼容端点
pub struct OpenAiProvider {
    name: String,
    default_base_url: String,
    /// 额外的 HTTP headers（例如 OpenRouter 的 HTTP-Referer、X-Title）
    extra_headers: Vec<(String, String)>,
    /// 是否解析内联 ` thinking` 标签（自定义端点通常需要）
    parse_inline_think_tags: bool,
    /// 是否在 404 时重试带 /v1 前缀的 URL（自定义端点通常需要）
    retry_missing_v1_base_url: bool,
    /// 是否使用 `reasoning_details` 字段（OpenRouter）而非 `reasoning_content`
    uses_reasoning_details: bool,
    /// 是否为推理模型（用于 OpenAI 的 reasoning_effort 参数）
    /// 对于 OpenAI，o1/o3/o4/gpt-5 系列模型需要设置 reasoning_effort
    is_reasoning_model: bool,
}

impl OpenAiProvider {
    // ── 构造函数 ──

    /// 创建 OpenAI 提供商
    pub fn new_openai() -> Self {
        Self {
            name: "OpenAI".to_string(),
            default_base_url: "https://api.openai.com/v1".to_string(),
            extra_headers: Vec::new(),
            parse_inline_think_tags: false,
            retry_missing_v1_base_url: false,
            uses_reasoning_details: false,
            is_reasoning_model: false,
        }
    }

    /// 创建 DeepSeek 提供商
    pub fn new_deepseek() -> Self {
        Self {
            name: "DeepSeek".to_string(),
            default_base_url: "https://api.deepseek.com".to_string(),
            extra_headers: Vec::new(),
            parse_inline_think_tags: false,
            retry_missing_v1_base_url: false,
            uses_reasoning_details: false,
            is_reasoning_model: false,
        }
    }

    /// 创建 Groq 提供商
    pub fn new_groq() -> Self {
        Self {
            name: "Groq".to_string(),
            default_base_url: "https://api.groq.com/openai/v1".to_string(),
            extra_headers: Vec::new(),
            parse_inline_think_tags: false,
            retry_missing_v1_base_url: false,
            uses_reasoning_details: false,
            is_reasoning_model: false,
        }
    }

    /// 创建 Qwen (通义千问) 提供商
    pub fn new_qwen() -> Self {
        Self {
            name: "Qwen".to_string(),
            default_base_url: "https://dashscope-intl.aliyuncs.com/compatible-mode/v1".to_string(),
            extra_headers: Vec::new(),
            parse_inline_think_tags: false,
            retry_missing_v1_base_url: false,
            uses_reasoning_details: false,
            is_reasoning_model: false,
        }
    }

    /// 创建 OpenRouter 提供商
    pub fn new_openrouter() -> Self {
        Self {
            name: "Open Router".to_string(),
            default_base_url: "https://openrouter.ai/api/v1".to_string(),
            extra_headers: vec![
                (
                    "HTTP-Referer".to_string(),
                    "https://github.com/newo-ether/Agora".to_string(),
                ),
                ("X-Title".to_string(), "Agora".to_string()),
            ],
            parse_inline_think_tags: false,
            retry_missing_v1_base_url: false,
            uses_reasoning_details: true,
            is_reasoning_model: false,
        }
    }

    /// 创建自定义 OpenAI 兼容提供商
    pub fn new_custom(name: String, base_url: String) -> Self {
        Self {
            name,
            default_base_url: base_url,
            extra_headers: Vec::new(),
            parse_inline_think_tags: true,
            retry_missing_v1_base_url: true,
            uses_reasoning_details: false,
            is_reasoning_model: false,
        }
    }

    // ── 请求构建 ──

    /// 构建 HTTP 请求 headers
    fn build_headers(&self, api_key: &str) -> HashMap<String, String> {
        let mut headers = HashMap::new();
        headers.insert("Content-Type".to_string(), "application/json".to_string());
        if !api_key.is_empty() {
            headers.insert("Authorization".to_string(), format!("Bearer {}", api_key));
        }
        for (key, value) in &self.extra_headers {
            headers.insert(key.clone(), value.clone());
        }
        headers
    }

    /// 构建端点候选 URL 列表
    fn endpoint_candidates(&self, base_url: &str, path: &str) -> Vec<String> {
        let normalized = base_url.trim_end_matches('/');
        let clean_path = path.trim_start_matches('/');
        let primary = format!("{}/{}", normalized, clean_path);

        if !self.retry_missing_v1_base_url || normalized.is_empty() {
            return vec![primary];
        }

        // 检查是否已有 /v1 段
        if normalized.ends_with("/v1") || normalized.contains("/v1/") {
            return vec![primary];
        }

        vec![primary, format!("{}/v1/{}", normalized, clean_path)]
    }

    /// 判断是否为 OpenAI 推理模型（o1/o3/o4/gpt-5）
    fn is_openai_reasoning_model(model_id: &str) -> bool {
        let m = model_id.to_lowercase();
        m.starts_with("o1") || m.starts_with("o3") || m.starts_with("o4") || m.starts_with("gpt-5")
    }

    /// 将 thinking_level 映射为 OpenAI reasoning_effort 值
    fn openai_reasoning_effort(level: &str) -> String {
        match level.to_lowercase().as_str() {
            "none" | "minimal" => "low".to_string(),
            "low" | "medium" | "high" => level.to_lowercase(),
            _ => "medium".to_string(),
        }
    }

    /// 将 thinking_level 映射为 OpenRouter reasoning_effort 值
    fn openrouter_reasoning_effort(level: &str) -> String {
        match level.to_lowercase().as_str() {
            "none" | "minimal" => "low".to_string(),
            "low" | "medium" | "high" | "xhigh" | "max" => level.to_lowercase(),
            _ => "medium".to_string(),
        }
    }

    // ── 消息转换 ──

    /// 将 ChatMessage 列表转换为 OpenAI 格式的消息列表
    fn convert_messages(
        messages: &[ChatMessage],
        system_prompt: Option<&str>,
        include_images: bool,
    ) -> Vec<OpenAiMessage> {
        let mut api_messages = Vec::new();

        // 系统提示
        if let Some(sp) = system_prompt {
            if !sp.trim().is_empty() {
                let content =
                    serde_json::Value::Array(vec![serde_json::json!({"type": "text", "text": sp})]);
                api_messages.push(OpenAiMessage {
                    role: "system".to_string(),
                    content: Some(content),
                    tool_calls: None,
                    tool_call_id: None,
                    reasoning_content: None,
                });
            }
        }

        for msg in messages {
            // 工具调用消息 (tool_ 前缀)
            if msg.id.starts_with(TOOL_MSG_PREFIX) {
                if let Some(ref tc_json) = msg.tool_call_json {
                    if let Ok(tool_calls) = Self::parse_tool_call_json(tc_json) {
                        api_messages.push(OpenAiMessage {
                            role: "assistant".to_string(),
                            content: None,
                            tool_calls: Some(tool_calls),
                            tool_call_id: None,
                            reasoning_content: msg.thoughts.clone(),
                        });
                        continue;
                    }
                }
                // 无法解析的 tool_ 消息，跳过
                continue;
            }

            // 工具结果消息 (result_ 前缀)
            if msg.id.starts_with(RESULT_MSG_PREFIX) {
                if let Some(ref tc_json) = msg.tool_call_json {
                    if let Ok(tool_calls) = Self::parse_tool_call_json(tc_json) {
                        for tc in &tool_calls {
                            api_messages.push(OpenAiMessage {
                                role: "tool".to_string(),
                                content: Some(serde_json::Value::Array(vec![
                                    serde_json::json!({"type": "text", "text": msg.text.clone()}),
                                ])),
                                tool_calls: None,
                                tool_call_id: Some(tc.id.clone()),
                                reasoning_content: None,
                            });
                        }
                        continue;
                    }
                }
                continue;
            }

            // 普通消息
            let role = match msg.participant {
                Participant::User => "user",
                _ => "assistant",
            };

            let mut parts = Vec::new();

            // 文本内容
            let text = msg.text.trim();
            if !text.is_empty() {
                parts.push(serde_json::json!({"type": "text", "text": text}));
            }

            // 图像（仅用户消息）
            if include_images && matches!(msg.participant, Participant::User) {
                for image in &msg.images {
                    if image.is_empty() {
                        continue;
                    }
                    // 图像可能是 data URL 或文件路径
                    if image.starts_with("data:") {
                        parts.push(serde_json::json!({
                            "type": "image_url",
                            "image_url": {"url": image}
                        }));
                    } else {
                        // 是文件路径，尝试编码
                        if let Some((mime, b64)) = Self::encode_image_file(image) {
                            parts.push(serde_json::json!({
                                "type": "image_url",
                                "image_url": {
                                    "url": format!("data:{};base64,{}", mime, b64)
                                }
                            }));
                        }
                    }
                }
            }

            if parts.is_empty() {
                parts.push(serde_json::json!({"type": "text", "text": "[Attachment unavailable]"}));
            }

            let content = serde_json::Value::Array(parts);

            api_messages.push(OpenAiMessage {
                role: role.to_string(),
                content: Some(content),
                tool_calls: None,
                tool_call_id: None,
                reasoning_content: None,
            });
        }

        api_messages
    }

    /// 解析 tool_call_json 为 OpenAI 工具调用列表
    fn parse_tool_call_json(
        json_str: &str,
    ) -> Result<Vec<OpenAiRequestToolCall>, serde_json::Error> {
        let parsed: serde_json::Value = serde_json::from_str(json_str)?;

        // 尝试解析为单个 ToolCallRequestData
        if let Ok(single) = serde_json::from_str::<ToolCallRequestData>(json_str) {
            return Ok(vec![OpenAiRequestToolCall {
                id: single.id,
                r#type: "function".to_string(),
                function: OpenAiRequestFunction {
                    name: single.name,
                    arguments: single.arguments,
                },
            }]);
        }

        // 尝试解析为数组
        if let Some(arr) = parsed.as_array() {
            let mut calls = Vec::new();
            for item in arr {
                if let Ok(tc) = serde_json::from_value::<ToolCallRequestData>(item.clone()) {
                    calls.push(OpenAiRequestToolCall {
                        id: tc.id,
                        r#type: "function".to_string(),
                        function: OpenAiRequestFunction {
                            name: tc.name,
                            arguments: tc.arguments,
                        },
                    });
                }
            }
            if !calls.is_empty() {
                return Ok(calls);
            }
        }

        Err(serde_json::Error::custom("无法解析 tool_call_json"))
    }

    /// 读取图像文件并编码为 base64
    fn encode_image_file(path: &str) -> Option<(String, String)> {
        use std::fs;
        let mut file = fs::File::open(path).ok()?;
        let mut bytes = Vec::new();
        std::io::Read::read_to_end(&mut file, &mut bytes).ok()?;
        use base64::Engine;
        let b64 = base64::engine::general_purpose::STANDARD.encode(&bytes);
        let mime = Self::mime_type_from_path(path);
        Some((mime.to_string(), b64))
    }

    /// 根据文件扩展名推断 MIME 类型
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

    // ── 请求自定义 ──

    /// 自定义请求体（添加 reasoning_effort、reasoning、plugins 等）
    fn customize_request(&self, request: &mut OpenAiChatRequest, config: &ProviderConfig) {
        // OpenAI 推理模型：添加 reasoning_effort
        if self.name == "OpenAI"
            && config.thinking_enabled
            && Self::is_openai_reasoning_model(&config.model_id)
        {
            request.reasoning_effort = Some(Self::openai_reasoning_effort(&config.thinking_level));
        }

        // OpenRouter：添加 reasoning 配置
        if self.name == "Open Router" && config.thinking_enabled {
            let effort = if !config.thinking_budget_enabled {
                Some(Self::openrouter_reasoning_effort(&config.thinking_level))
            } else {
                None
            };
            let max_tokens = if config.thinking_budget_enabled {
                Some(config.thinking_budget_tokens)
            } else {
                None
            };
            request.reasoning = Some(OpenAiReasoning { effort, max_tokens });
        }
    }

    // ── 流式 SSE 解析 ──

    /// 解析 SSE 事件流，返回 StreamEvent 序列。
    ///
    /// 使用 engine_linux01 的 unfold 模式（`parse_sse_json_stream`）组织流对，
    /// 通过 `SseEvent` 枚举处理 JSON 数据块、DONE 标记和原始块。
    /// 自动处理 UTF-8 边界（参考 cc-switch 的 `append_utf8_safe`）。
    async fn parse_sse_stream(
        &self,
        stream_response: crate::api::http_client::StreamResponse,
        config: &ProviderConfig,
    ) -> Result<Vec<StreamEvent>, AgoraError> {
        let mut events: Vec<StreamEvent> = Vec::new();

        // 工具调用累积状态
        let mut pending_tool_calls: HashMap<i32, PendingToolCall> = HashMap::new();
        let mut structured_tool_calls_emitted = false;
        let mut content_buffer = String::new();

        // 思考标签解析器（仅自定义端点）
        let mut think_parser = if self.parse_inline_think_tags {
            Some(ThinkTagParser::new())
        } else {
            None
        };

        // ── 使用 unfold 模式的 SSE JSON 流解析器 ──
        let byte_stream = stream_response.into_stream();
        let mut sse_stream = parse_sse_json_stream(byte_stream);

        loop {
            match sse_stream.next().await {
                Some(Ok(SseEvent::JsonValue(value))) => {
                    // 尝试解析为 OpenAI 流响应
                    let response: OpenAiStreamResponse =
                        match serde_json::from_value(value.clone()) {
                            Ok(r) => r,
                            Err(_) => {
                                // 尝试解析为错误响应
                                if let Ok(err_resp) =
                                    serde_json::from_value::<OpenAiErrorResponse>(value)
                                {
                                    events.push(StreamEvent::Error {
                                        error: GenerationError::Api {
                                            code: err_resp.error.code.unwrap_or_default(),
                                            message: err_resp.error.message,
                                            error_type: err_resp.error.r#type,
                                        },
                                    });
                                }
                                continue;
                            }
                        };

                    let choice = response.choices.as_ref().and_then(|c| c.first());

                    // ── 处理 delta ──
                    if let Some(delta) = choice.and_then(|c| c.delta.as_ref()) {
                        // 处理 reasoning_content / content
                        Self::handle_delta_content(
                            delta,
                            config,
                            &mut events,
                            &mut content_buffer,
                            &mut think_parser,
                            self.uses_reasoning_details,
                        );

                        // 处理 tool_calls
                        if let Some(tool_calls) = &delta.tool_calls {
                            for tc in tool_calls {
                                let idx = tc.index.unwrap_or(pending_tool_calls.len() as i32);

                                let existing = tc
                                    .id
                                    .as_ref()
                                    .and_then(|id| {
                                        pending_tool_calls
                                            .values()
                                            .find(|p| p.id == *id)
                                    })
                                    .map(|_| ());

                                let pending = if existing.is_some() {
                                    let key = pending_tool_calls
                                        .iter()
                                        .find(|(_, v)| v.id == *tc.id.as_ref().unwrap())
                                        .map(|(k, _)| *k)
                                        .unwrap_or(idx);
                                    pending_tool_calls.get_mut(&key).unwrap()
                                } else {
                                    pending_tool_calls
                                        .entry(idx)
                                        .or_insert_with(PendingToolCall::default)
                                };

                                if let Some(ref id) = tc.id {
                                    pending.id = id.clone();
                                }
                                if let Some(ref func) = tc.function {
                                    if let Some(ref name) = func.name {
                                        if !name.is_empty() {
                                            pending.name = name.clone();
                                        }
                                    }
                                    if let Some(ref args) = func.arguments {
                                        pending.args.push_str(args);
                                    }
                                }
                            }
                        }
                    }

                    // ── 处理 finish_reason → 工具调用发射 ──
                    if let Some(choice) = choice {
                        if let Some(ref finish_reason) = choice.finish_reason {
                            if finish_reason == "tool_calls"
                                && !pending_tool_calls.is_empty()
                            {
                                let calls: Vec<ToolCallRequestData> =
                                    pending_tool_calls
                                        .values()
                                        .filter(|p| !p.name.is_empty())
                                        .map(|p| ToolCallRequestData {
                                            id: p.id.clone(),
                                            name: p.name.clone(),
                                            arguments: p.args.clone(),
                                            signature: None,
                                        })
                                        .collect();

                                pending_tool_calls.clear();

                                if calls.len() == 1 {
                                    structured_tool_calls_emitted = true;
                                    let c = &calls[0];
                                    events.push(StreamEvent::ToolCallRequest {
                                        id: c.id.clone(),
                                        name: c.name.clone(),
                                        arguments: c.arguments.clone(),
                                        signature: None,
                                    });
                                } else if calls.len() > 1 {
                                    structured_tool_calls_emitted = true;
                                    events.push(StreamEvent::ToolCallsRequest { calls });
                                }
                            }
                        }
                    }

                    // ── 处理 usage ──
                    if let Some(usage) = &response.usage {
                        let thoughts_token_count = usage
                            .completion_tokens_details
                            .as_ref()
                            .and_then(|d| d.reasoning_tokens)
                            .unwrap_or(0);
                        events.push(StreamEvent::UsageUpdate {
                            token_count: usage.total_tokens,
                            thoughts_token_count,
                        });
                    }
                }
                Some(Ok(SseEvent::Done)) => {
                    break;
                }
                Some(Ok(SseEvent::RawBlock(_))) => {
                    // 非 data 字段的 SSE 块，忽略
                }
                Some(Err(e)) => {
                    return Err(e);
                }
                None => {
                    break;
                }
            }
        }

        // ── 刷新思考标签解析器 ──
        if let Some(ref mut parser) = think_parser {
            let (texts, thoughts) = parser.flush();
            for t in texts {
                if !t.is_empty() {
                    content_buffer.push_str(&t);
                    events.push(StreamEvent::TextChunk { text: t });
                }
            }
            for t in thoughts {
                if !t.is_empty() {
                    events.push(StreamEvent::ThoughtChunk {
                        thought: t,
                        title: None,
                        signature: None,
                    });
                }
            }
        }

        // ── 回退路径：如果工具调用未通过结构化字段发出，尝试从内容中解析 ──
        if !structured_tool_calls_emitted
            && config
                .tools
                .as_ref()
                .map(|t| !t.is_empty())
                .unwrap_or(false)
            && !content_buffer.is_empty()
        {
            if let Some(tool_calls) = Self::try_parse_tool_calls_from_text(&content_buffer) {
                if tool_calls.len() == 1 {
                    let tc = &tool_calls[0];
                    events.push(StreamEvent::ToolCallRequest {
                        id: tc.id.clone(),
                        name: tc.name.clone(),
                        arguments: tc.arguments.clone(),
                        signature: None,
                    });
                } else if tool_calls.len() > 1 {
                    events.push(StreamEvent::ToolCallsRequest { calls: tool_calls });
                }
            }
        }

        Ok(events)
    }

    /// 从 SSE 块中提取 data 字段值
    fn extract_sse_data(block: &str) -> Option<String> {
        let mut data_parts = Vec::new();
        for line in block.lines() {
            if let Some(val) = strip_sse_field(line, "data") {
                data_parts.push(val.to_string());
            }
        }
        if data_parts.is_empty() {
            None
        } else {
            Some(data_parts.join("\n"))
        }
    }

    /// 处理 delta 内容（reasoning + text content）
    fn handle_delta_content(
        delta: &OpenAiDelta,
        config: &ProviderConfig,
        events: &mut Vec<StreamEvent>,
        content_buffer: &mut String,
        think_parser: &mut Option<ThinkTagParser>,
        uses_reasoning_details: bool,
    ) {
        // ── reasoning_content 字段（DeepSeek / Qwen / 标准 OpenAI 兼容）──
        if !uses_reasoning_details {
            if let Some(ref reasoning) = delta.reasoning {
                if !reasoning.is_empty() && config.thinking_enabled {
                    events.push(StreamEvent::ThoughtChunk {
                        thought: reasoning.clone(),
                        title: None,
                        signature: None,
                    });
                }
            }
        }

        // ── content 字段 ──
        if let Some(ref content) = delta.content {
            if !content.is_empty() {
                content_buffer.push_str(content);

                if let Some(ref mut parser) = think_parser {
                    let (texts, thoughts) = parser.feed(content);
                    for t in texts {
                        if !t.is_empty() {
                            events.push(StreamEvent::TextChunk { text: t });
                        }
                    }
                    for t in thoughts {
                        if !t.is_empty() {
                            events.push(StreamEvent::ThoughtChunk {
                                thought: t,
                                title: None,
                                signature: None,
                            });
                        }
                    }
                } else {
                    events.push(StreamEvent::TextChunk {
                        text: content.clone(),
                    });
                }
            }
        }
    }

    /// 尝试从文本内容中解析工具调用 JSON（回退路径）
    fn try_parse_tool_calls_from_text(text: &str) -> Option<Vec<ToolCallRequestData>> {
        let trimmed = text.trim();

        // 尝试解析为 JSON 数组
        if let Ok(arr) = serde_json::from_str::<Vec<serde_json::Value>>(trimmed) {
            let mut calls = Vec::new();
            for item in arr {
                let name = item
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let arguments = item
                    .get("arguments")
                    .map(|v| v.to_string())
                    .unwrap_or_else(|| "{}".to_string());
                if !name.is_empty() {
                    calls.push(ToolCallRequestData {
                        id: format!("call_text_{}", uuid::Uuid::new_v4()),
                        name,
                        arguments,
                        signature: None,
                    });
                }
            }
            if !calls.is_empty() {
                return Some(calls);
            }
        }

        // 尝试解析为单个 JSON 对象
        if let Ok(obj) = serde_json::from_str::<serde_json::Value>(trimmed) {
            let name = obj
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let arguments = obj
                .get("arguments")
                .map(|v| v.to_string())
                .unwrap_or_else(|| "{}".to_string());
            if !name.is_empty() {
                return Some(vec![ToolCallRequestData {
                    id: format!("call_text_{}", uuid::Uuid::new_v4()),
                    name,
                    arguments,
                    signature: None,
                }]);
            }
        }

        None
    }
}

// ============================================================
// LlmProvider trait 实现
// ============================================================

#[async_trait]
impl LlmProvider for OpenAiProvider {
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

        let endpoint_urls = self.endpoint_candidates(base_url, "chat/completions");

        // ── 消息准备管道（对应 Kotlin prepareMessages + convertToOpenAiMessages）──
        // 1. 先经过消息准备管道（去重、状态投影、工具验证、空过滤、合并、截断）
        let prepared_messages = message_pipeline::prepare_messages(messages, config.max_context_window);

        // 2. 助手图像投影到最新用户消息
        let prepared_messages = message_pipeline::project_assistant_images_to_latest_user_message(
            &prepared_messages,
            config.include_images,
        );

        // 3. 转换为 OpenAI 格式
        let api_messages = Self::convert_messages(
            &prepared_messages,
            config.system_prompt.as_deref(),
            config.include_images,
        );

        // ── 构建请求体 ──
        let mut request = OpenAiChatRequest {
            model: config.model_id.clone(),
            messages: api_messages,
            stream: true,
            stream_options: Some(OpenAiStreamOptions {
                include_usage: true,
            }),
            tools: config.tools.clone(),
            reasoning_effort: None,
            reasoning: None,
            temperature: config.temperature,
            max_tokens: config.max_tokens,
            top_p: config.top_p,
            frequency_penalty: config.frequency_penalty,
            presence_penalty: config.presence_penalty,
        };

        self.customize_request(&mut request, config);

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

        let json_body = serde_json::to_value(&request)?;
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

        let headers = self.build_headers(&config.api_key);

        // ── 重试循环 ──
        let max_attempts = 3;
        let mut last_error: Option<AgoraError> = None;
        let mut retry_events: Vec<StreamEvent> = Vec::new();

        for attempt in 1..=max_attempts {
            let mut endpoint_index = 0;
            let mut retry_this_attempt = false;

            while endpoint_index < endpoint_urls.len() {
                let endpoint_url = &endpoint_urls[endpoint_index];

                let mut stream_response =
                    match client.stream_post(endpoint_url, &json_body, &headers).await {
                        Ok(resp) => resp,
                        Err(e) => {
                            // 连接/超时错误，尝试下一个端点
                            if endpoint_index + 1 < endpoint_urls.len() {
                                endpoint_index += 1;
                                continue;
                            }
                            last_error = Some(e);
                            break;
                        }
                    };

                // 如果 HTTP 状态码是 200，解析流
                if stream_response.code == 200 {
                    match self.parse_sse_stream(stream_response, config).await {
                        Ok(mut events) => {
                            // 如果有重试事件，合并到结果中
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

                // 非 200 状态码
                // 尝试下一个端点
                if endpoint_index + 1 < endpoint_urls.len() {
                    endpoint_index += 1;
                    continue;
                }

                // 可重试状态码
                let retryable = matches!(stream_response.code, 429 | 502 | 503 | 504);
                if retryable && attempt < max_attempts {
                    retry_this_attempt = true;
                    // 发出 Retrying 事件
                    retry_events.push(StreamEvent::Retrying {
                        attempt: attempt as i32,
                        max_attempts: max_attempts as i32,
                    });
                    // 等待后重试（指数退避）
                    tokio::time::sleep(std::time::Duration::from_millis(1000 * attempt as u64))
                        .await;
                    break;
                }

                // 不可重试的错误
                let error_body = stream_response.error_body.unwrap_or_default();
                last_error = Some(match stream_response.code {
                    401 => AgoraError::Api {
                        code: "401".to_string(),
                        message: format!("Authentication failed: {}. Check your API key.", error_body),
                    },
                    403 => AgoraError::Api {
                        code: "403".to_string(),
                        message: format!("Access forbidden: {}", error_body),
                    },
                    404 => {
                        let hint = if endpoint_urls.len() > 1 {
                            format!("\nTried {} and {}. OpenAI-compatible servers often require a /v1 Base URL.",
                                endpoint_urls[0], endpoint_urls.last().unwrap())
                        } else {
                            String::new()
                        };
                        AgoraError::Api {
                            code: "404".to_string(),
                            message: format!("Not found: {}{}", error_body, hint),
                        }
                    }
                    _ => AgoraError::Network {
                        status_code: stream_response.code as i32,
                        message: format!("HTTP {} from {}: {}", stream_response.code, endpoint_url, error_body),
                    },
                });
                break;
            }

            if !retry_this_attempt {
                break;
            }
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
        let effective_base_url = base_url
            .map(|s| s.trim_end_matches('/'))
            .filter(|s| !s.is_empty())
            .unwrap_or(&self.default_base_url);

        let endpoint_urls = self.endpoint_candidates(effective_base_url, "models");
        let headers = self.build_headers(api_key);

        let mut last_error: Option<AgoraError> = None;

        for endpoint_url in &endpoint_urls {
            match client.fetch_models(endpoint_url, &headers).await {
                Ok(response_text) => {
                    if let Ok(model_list) =
                        serde_json::from_str::<OpenAiModelListResponse>(&response_text)
                    {
                        let mut model_ids: Vec<String> =
                            model_list.data.into_iter().map(|m| m.id).collect();
                        model_ids.sort();
                        return Ok(model_ids);
                    }
                    last_error = Some(AgoraError::Parse("Failed to parse model list".to_string()));
                }
                Err(e) => {
                    last_error = Some(e);
                }
            }
        }

        Err(last_error.unwrap_or_else(|| AgoraError::Unknown("Failed to fetch models".to_string())))
    }
}

// ============================================================
// ThinkTagParser — 内联思考标签解析器
// ============================================================

/// 简化的内联思考标签解析器。
///
/// 解析 ` thinking` / ` response` 以及 `<thinking>` / `</thinking>` 等标签，
/// 将内容分流为文本块和思考块。
///
/// 参考 Kotlin IncrementalThinkingParser 的实现。
struct ThinkTagParser {
    /// 累积的待处理内容
    buffer: String,
    /// 是否在思考块内
    in_thinking: bool,
    /// 当前思考块的起始标签
    start_tag: Option<String>,
}

/// 思考标签定义
struct ThinkMarker {
    start: &'static str,
    ends: &'static [&'static str],
}

impl ThinkTagParser {
    fn new() -> Self {
        Self {
            buffer: String::new(),
            in_thinking: false,
            start_tag: None,
        }
    }

    /// 喂入新的内容，返回 (文本块列表, 思考块列表)
    fn feed(&mut self, content: &str) -> (Vec<String>, Vec<String>) {
        self.buffer.push_str(content);
        self.drain(false)
    }

    /// 刷新剩余内容，返回 (文本块列表, 思考块列表)
    fn flush(&mut self) -> (Vec<String>, Vec<String>) {
        self.drain(true)
    }

    fn drain(&mut self, finalize: bool) -> (Vec<String>, Vec<String>) {
        let mut texts = Vec::new();
        let mut thoughts = Vec::new();

        let markers = Self::all_markers();
        let mut index = 0;

        loop {
            if index >= self.buffer.len() {
                break;
            }

            let remainder = &self.buffer[index..];

            if self.in_thinking {
                // 在思考块内，查找结束标签
                if let Some(ref start_tag) = self.start_tag {
                    let ends = Self::get_end_tags_for(start_tag, &markers);
                    if let Some(end_match) = Self::find_end_match(remainder, ends, finalize) {
                        if let Some(end_len) = end_match {
                            // 找到结束标签
                            let thought = self.buffer[..index].to_string();
                            if !thought.is_empty() {
                                thoughts.push(thought);
                            }
                            self.buffer = self.buffer[index + end_len..].to_string();
                            self.in_thinking = false;
                            self.start_tag = None;
                            index = 0;
                            continue;
                        } else {
                            // 部分匹配，等待更多数据
                            if !finalize {
                                break;
                            }
                            // 最终刷新，不等待
                            index += 1;
                            continue;
                        }
                    }
                }
                // 没有匹配的结束标签，继续前进
                index += 1;
            } else {
                // 在思考块外，查找开始标签
                if let Some(start_match) = Self::find_start_match(remainder, &markers, finalize) {
                    if let Some((marker, tag_len)) = start_match {
                        // 找到开始标签
                        if index > 0 {
                            let text = self.buffer[..index].to_string();
                            if !text.is_empty() {
                                texts.push(text);
                            }
                        }
                        self.buffer = self.buffer[index + tag_len..].to_string();
                        self.in_thinking = true;
                        self.start_tag = Some(marker.start.to_string());
                        index = 0;
                        continue;
                    } else {
                        // 部分匹配，等待更多数据
                        if !finalize {
                            break;
                        }
                        index += 1;
                        continue;
                    }
                }
                index += 1;
            }
        }

        // 清理已消费的内容
        if index > 0 {
            let remaining = self.buffer[index..].to_string();
            if !self.in_thinking {
                if !self.buffer[..index].is_empty() {
                    texts.push(self.buffer[..index].to_string());
                }
                self.buffer = remaining;
            } else {
                // 在思考块内，保留所有内容
                let _ = remaining;
            }
        }

        // 最终刷新：如果还有剩余内容
        if finalize && !self.buffer.is_empty() {
            if self.in_thinking {
                thoughts.push(self.buffer.clone());
            } else {
                texts.push(self.buffer.clone());
            }
            self.buffer.clear();
            self.in_thinking = false;
            self.start_tag = None;
        }

        (texts, thoughts)
    }

    fn all_markers() -> Vec<ThinkMarker> {
        vec![
            ThinkMarker {
                start: " thinking",
                ends: &[" response"],
            },
            ThinkMarker {
                start: "<thinking>",
                ends: &["</thinking>"],
            },
            ThinkMarker {
                start: "<reasoning>",
                ends: &["</reasoning>"],
            },
            ThinkMarker {
                start: "<analysis>",
                ends: &["</analysis>"],
            },
            ThinkMarker {
                start: "<thought>",
                ends: &["</thought>"],
            },
        ]
    }

    fn get_end_tags_for<'a>(start: &str, markers: &'a [ThinkMarker]) -> &'a [&'static str] {
        for m in markers {
            if m.start == start {
                return m.ends;
            }
        }
        &[]
    }

    /// 查找开始标签匹配
    /// 返回 Some((marker_ref, tag_length)) 表示完全匹配，None 表示无匹配
    /// 返回 Some(None) 表示部分匹配
    fn find_start_match(
        remainder: &str,
        markers: &[ThinkMarker],
        finalize: bool,
    ) -> Option<Option<(&ThinkMarker, usize)>> {
        for marker in markers {
            match Self::prefix_match(remainder, marker.start) {
                MatchResult::Full(len) => return Some(Some((marker, len))),
                MatchResult::Partial => {
                    if !finalize {
                        return Some(None);
                    }
                }
                MatchResult::None => {}
            }
        }
        None
    }

    /// 查找结束标签匹配
    fn find_end_match(remainder: &str, ends: &[&str], finalize: bool) -> Option<Option<usize>> {
        for end in ends {
            match Self::prefix_match(remainder, end) {
                MatchResult::Full(len) => return Some(Some(len)),
                MatchResult::Partial => {
                    if !finalize {
                        return Some(None);
                    }
                }
                MatchResult::None => {}
            }
        }
        None
    }

    fn prefix_match(text: &str, prefix: &str) -> MatchResult {
        if text.starts_with(prefix) {
            return MatchResult::Full(prefix.len());
        }
        if text.len() < prefix.len() && prefix.starts_with(text) {
            return MatchResult::Partial;
        }
        MatchResult::None
    }
}

enum MatchResult {
    Full(usize),
    Partial,
    None,
}

// ============================================================
// 测试
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    // ── ThinkTagParser 测试 ──

    #[test]
    fn test_think_parser_think_response_tags() {
        let mut parser = ThinkTagParser::new();
        let (texts, thoughts) = parser.feed("Hello  thinkingthis is a thought response world");
        // 刷新
        let (texts2, thoughts2) = parser.flush();

        let all_texts: Vec<String> = texts.into_iter().chain(texts2).collect();
        let all_thoughts: Vec<String> = thoughts.into_iter().chain(thoughts2).collect();

        assert_eq!(all_texts.join(""), "Hello  world");
        assert_eq!(all_thoughts.join(""), "this is a thought");
    }

    #[test]
    fn test_think_parser_xml_tags() {
        let mut parser = ThinkTagParser::new();
        let (texts, thoughts) = parser.feed("before <thinking>inside</thinking> after");
        let (texts2, thoughts2) = parser.flush();

        let all_texts: Vec<String> = texts.into_iter().chain(texts2).collect();
        let all_thoughts: Vec<String> = thoughts.into_iter().chain(thoughts2).collect();

        assert_eq!(all_texts.join(""), "before  after");
        assert_eq!(all_thoughts.join(""), "inside");
    }

    #[test]
    fn test_think_parser_chunked_start() {
        let mut parser = ThinkTagParser::new();
        let (texts, thoughts) = parser.feed("Hello  think");
        assert!(texts.iter().all(|t| t == "Hello "));
        assert!(thoughts.is_empty());

        let (texts2, thoughts2) = parser.feed("inginside thought response world");
        let (texts3, thoughts3) = parser.flush();

        let all_thoughts: Vec<String> = thoughts
            .into_iter()
            .chain(thoughts2)
            .chain(thoughts3)
            .collect();
        assert_eq!(all_thoughts.join(""), "inside thought");
    }

    #[test]
    fn test_think_parser_no_tags() {
        let mut parser = ThinkTagParser::new();
        let (texts, thoughts) = parser.feed("Hello world");
        let (texts2, thoughts2) = parser.flush();

        let all_texts: Vec<String> = texts.into_iter().chain(texts2).collect();
        let all_thoughts: Vec<String> = thoughts.into_iter().chain(thoughts2).collect();

        assert_eq!(all_texts.join(""), "Hello world");
        assert!(all_thoughts.is_empty());
    }

    #[test]
    fn test_think_parser_multiple_blocks() {
        let mut parser = ThinkTagParser::new();
        let (texts, _) = parser.feed("a <thinking>b</thinking> c <reasoning>d</reasoning> e");
        let (texts2, thoughts2) = parser.flush();

        let all_texts: Vec<String> = texts.into_iter().chain(texts2).collect();
        assert_eq!(all_texts.join(""), "a  c  e");

        // 刷新后思考块会被收集
        assert!(!thoughts2.is_empty());
    }

    #[test]
    fn test_think_parser_unclosed_flush() {
        let mut parser = ThinkTagParser::new();
        let (texts, _) = parser.feed("before <thinking>unclosed");
        assert_eq!(texts.join(""), "before ");

        let (texts2, thoughts2) = parser.flush();
        assert!(texts2.is_empty());
        assert_eq!(thoughts2.join(""), "unclosed");
    }

    // ── SSE 辅助函数测试 ──

    #[test]
    fn test_extract_sse_data_single_line() {
        let block = "data: {\"key\":\"value\"}";
        let result = OpenAiProvider::extract_sse_data(block);
        assert_eq!(result, Some("{\"key\":\"value\"}".to_string()));
    }

    #[test]
    fn test_extract_sse_data_multiline() {
        let block = "data: line1\ndata: line2";
        let result = OpenAiProvider::extract_sse_data(block);
        assert_eq!(result, Some("line1\nline2".to_string()));
    }

    #[test]
    fn test_extract_sse_data_no_data_field() {
        let block = "event: message\nid: 123";
        let result = OpenAiProvider::extract_sse_data(block);
        assert_eq!(result, None);
    }

    #[test]
    fn test_extract_sse_data_empty() {
        let block = "";
        let result = OpenAiProvider::extract_sse_data(block);
        assert_eq!(result, None);
    }

    #[test]
    fn test_extract_sse_data_done() {
        let block = "data: [DONE]";
        let result = OpenAiProvider::extract_sse_data(block);
        assert_eq!(result, Some("[DONE]".to_string()));
    }

    // ── 消息转换测试 ──

    #[test]
    fn test_convert_messages_basic_user() {
        let messages = vec![ChatMessage {
            id: "msg1".to_string(),
            parent_id: None,
            text: "Hello".to_string(),
            images: vec![],
            thoughts: None,
            thought_title: None,
            token_count: 0,
            status: MessageStatus::Success,
            participant: Participant::User,
            timestamp: 0,
            thought_time_ms: None,
            model_name: None,
            tool_call_json: None,
            attachment_meta: None,
            retry_text: None,
        }];

        let result = OpenAiProvider::convert_messages(&messages, None, false);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].role, "user");
    }

    #[test]
    fn test_convert_messages_system_prompt() {
        let messages = vec![];
        let result = OpenAiProvider::convert_messages(&messages, Some("You are helpful"), false);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].role, "system");
    }

    #[test]
    fn test_convert_messages_assistant() {
        let messages = vec![ChatMessage {
            id: "msg2".to_string(),
            parent_id: None,
            text: "Hi there".to_string(),
            images: vec![],
            thoughts: None,
            thought_title: None,
            token_count: 0,
            status: MessageStatus::Success,
            participant: Participant::Model,
            timestamp: 0,
            thought_time_ms: None,
            model_name: None,
            tool_call_json: None,
            attachment_meta: None,
            retry_text: None,
        }];

        let result = OpenAiProvider::convert_messages(&messages, None, false);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].role, "assistant");
    }

    #[test]
    fn test_convert_messages_empty_text() {
        let messages = vec![ChatMessage {
            id: "msg3".to_string(),
            parent_id: None,
            text: "".to_string(),
            images: vec![],
            thoughts: None,
            thought_title: None,
            token_count: 0,
            status: MessageStatus::Success,
            participant: Participant::User,
            timestamp: 0,
            thought_time_ms: None,
            model_name: None,
            tool_call_json: None,
            attachment_meta: None,
            retry_text: None,
        }];

        let result = OpenAiProvider::convert_messages(&messages, None, false);
        assert_eq!(result.len(), 1);
        // 空文本应该有占位内容
        if let Some(ref content) = result[0].content {
            let s = content.to_string();
            assert!(s.contains("Attachment unavailable"));
        }
    }

    // ── MIME 类型测试 ──

    #[test]
    fn test_mime_type_from_path() {
        assert_eq!(
            OpenAiProvider::mime_type_from_path("photo.png"),
            "image/png"
        );
        assert_eq!(
            OpenAiProvider::mime_type_from_path("photo.PNG"),
            "image/png"
        );
        assert_eq!(
            OpenAiProvider::mime_type_from_path("photo.webp"),
            "image/webp"
        );
        assert_eq!(
            OpenAiProvider::mime_type_from_path("photo.gif"),
            "image/gif"
        );
        assert_eq!(
            OpenAiProvider::mime_type_from_path("photo.jpg"),
            "image/jpeg"
        );
        assert_eq!(
            OpenAiProvider::mime_type_from_path("photo.jpeg"),
            "image/jpeg"
        );
    }

    // ── 推理模型检测测试 ──

    #[test]
    fn test_is_openai_reasoning_model() {
        assert!(OpenAiProvider::is_openai_reasoning_model("o1"));
        assert!(OpenAiProvider::is_openai_reasoning_model("o1-mini"));
        assert!(OpenAiProvider::is_openai_reasoning_model("o3"));
        assert!(OpenAiProvider::is_openai_reasoning_model("o3-mini"));
        assert!(OpenAiProvider::is_openai_reasoning_model("o4-mini"));
        assert!(OpenAiProvider::is_openai_reasoning_model("gpt-5"));
        assert!(!OpenAiProvider::is_openai_reasoning_model("gpt-4"));
        assert!(!OpenAiProvider::is_openai_reasoning_model("gpt-4o"));
    }

    // ── 端点候选 URL 测试 ──

    #[test]
    fn test_endpoint_candidates_normal() {
        let provider = OpenAiProvider::new_openai();
        let urls = provider.endpoint_candidates("https://api.openai.com/v1", "chat/completions");
        assert_eq!(urls.len(), 1);
        assert_eq!(urls[0], "https://api.openai.com/v1/chat/completions");
    }

    #[test]
    fn test_endpoint_candidates_custom_retry() {
        let provider = OpenAiProvider::new_custom(
            "Custom".to_string(),
            "https://my-llm.example.com".to_string(),
        );
        let urls = provider.endpoint_candidates("https://my-llm.example.com", "chat/completions");
        assert_eq!(urls.len(), 2);
        assert_eq!(urls[0], "https://my-llm.example.com/chat/completions");
        assert_eq!(urls[1], "https://my-llm.example.com/v1/chat/completions");
    }

    #[test]
    fn test_endpoint_candidates_already_has_v1() {
        let provider = OpenAiProvider::new_custom(
            "Custom".to_string(),
            "https://my-llm.example.com/v1".to_string(),
        );
        let urls =
            provider.endpoint_candidates("https://my-llm.example.com/v1", "chat/completions");
        assert_eq!(urls.len(), 1);
    }

    // ── 构造函数测试 ──

    #[test]
    fn test_constructor_names() {
        assert_eq!(OpenAiProvider::new_openai().name(), "OpenAI");
        assert_eq!(OpenAiProvider::new_deepseek().name(), "DeepSeek");
        assert_eq!(OpenAiProvider::new_groq().name(), "Groq");
        assert_eq!(OpenAiProvider::new_qwen().name(), "Qwen");
        assert_eq!(OpenAiProvider::new_openrouter().name(), "Open Router");
        let custom =
            OpenAiProvider::new_custom("MyProvider".to_string(), "https://example.com".to_string());
        assert_eq!(custom.name(), "MyProvider");
    }

    #[test]
    fn test_constructor_base_urls() {
        assert_eq!(
            OpenAiProvider::new_openai().default_base_url(),
            "https://api.openai.com/v1"
        );
        assert_eq!(
            OpenAiProvider::new_deepseek().default_base_url(),
            "https://api.deepseek.com"
        );
        assert_eq!(
            OpenAiProvider::new_groq().default_base_url(),
            "https://api.groq.com/openai/v1"
        );
        assert_eq!(
            OpenAiProvider::new_qwen().default_base_url(),
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        );
        assert_eq!(
            OpenAiProvider::new_openrouter().default_base_url(),
            "https://openrouter.ai/api/v1"
        );
    }

    #[test]
    fn test_custom_provider_parse_inline_think_tags() {
        let custom =
            OpenAiProvider::new_custom("Custom".to_string(), "https://example.com".to_string());
        assert!(custom.parse_inline_think_tags);
        assert!(custom.retry_missing_v1_base_url);
    }

    #[test]
    fn test_openrouter_uses_reasoning_details() {
        let or = OpenAiProvider::new_openrouter();
        assert!(or.uses_reasoning_details);
        assert!(!or.extra_headers.is_empty());
    }

    // ── 工具调用解析测试 ──

    #[test]
    fn test_try_parse_tool_calls_from_text_single() {
        let text = r#"{"name": "get_weather", "arguments": {"city": "Beijing"}}"#;
        let result = OpenAiProvider::try_parse_tool_calls_from_text(text);
        assert!(result.is_some());
        let calls = result.unwrap();
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "get_weather");
    }

    #[test]
    fn test_try_parse_tool_calls_from_text_array() {
        let text = r#"[{"name": "get_weather", "arguments": {"city": "Beijing"}}, {"name": "get_time", "arguments": {}}]"#;
        let result = OpenAiProvider::try_parse_tool_calls_from_text(text);
        assert!(result.is_some());
        let calls = result.unwrap();
        assert_eq!(calls.len(), 2);
    }

    #[test]
    fn test_try_parse_tool_calls_from_text_plain() {
        let text = "Hello, this is just text";
        let result = OpenAiProvider::try_parse_tool_calls_from_text(text);
        assert!(result.is_none());
    }
}
