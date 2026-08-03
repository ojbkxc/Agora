// Gemini Provider 实现
// 基于 Google Gemini API (generativelanguage.googleapis.com)
// 参考 Kotlin 实现: GeminiProvider.kt

use async_trait::async_trait;
use std::collections::HashMap;

use futures::StreamExt;

use crate::api::http_client::AgoraHttpClient;
use crate::api::message_pipeline;
use crate::api::provider::{LlmProvider, StreamCallback};
use crate::api::request_validator;
use crate::api::sse::{append_utf8_safe, strip_sse_field, take_sse_block};
use crate::api::types::*;
use crate::error::AgoraError;

// ============================================================
// GeminiProvider
// ============================================================

pub struct GeminiProvider {
    pub default_base_url: String,
}

impl GeminiProvider {
    pub fn new() -> Self {
        Self {
            default_base_url: "https://generativelanguage.googleapis.com/v1beta".to_string(),
        }
    }
}

impl Default for GeminiProvider {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl LlmProvider for GeminiProvider {
    fn name(&self) -> &str {
        "gemini"
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
            .unwrap_or(self.default_base_url());

        let clean_model = config.model_id.trim_start_matches("models/");

        // 1. 消息准备管道 + 助手图像投影 + 转换消息为 Gemini 格式
        let prepared_messages = message_pipeline::prepare_messages(messages, config.max_context_window);
        let prepared_messages = message_pipeline::project_assistant_images_to_latest_user_message(
            &prepared_messages,
            config.include_images,
        );
        let contents = build_contents(&prepared_messages, config)?;

        // 2. 构建 system_instruction
        let system_instruction = config.system_prompt.as_ref().map(|sp| GeminiContent {
            role: String::new(),
            parts: vec![GeminiPart {
                text: Some(sp.clone()),
                inline_data: None,
                function_call: None,
                function_response: None,
                thought_signature: None,
            }],
        });

        // 3. 构建 tools
        let tools = build_tools(config);

        // 4. 构建 generation_config
        let generation_config = build_generation_config(config, clean_model);

        let request = GeminiRequest {
            contents,
            system_instruction,
            generation_config,
            tools,
        };

        let mut request_json = serde_json::to_value(&request)?;

        // 如果同时有内置工具和函数声明，添加 toolConfig
        let has_builtin = config.code_execution_enabled || config.google_search_enabled;
        let has_functions = config
            .tools
            .as_ref()
            .map(|t| !t.is_empty())
            .unwrap_or(false);
        if has_builtin && has_functions {
            request_json["toolConfig"] =
                serde_json::json!({"includeServerSideToolInvocations": false});
        }

        // 5. 构建 URL
        let url = if base_url.contains("/v1") || base_url.contains("/v1beta") {
            format!(
                "{}/models/{}:streamGenerateContent?alt=sse",
                base_url, clean_model
            )
        } else {
            format!(
                "{}/v1beta/models/{}:streamGenerateContent?alt=sse",
                base_url, clean_model
            )
        };

        // 5.5 工具定义验证
        if let Some(ref tools) = config.tools {
            let violations = request_validator::validate_tool_definitions(tools);
            if !violations.is_empty() {
                on_event(StreamEvent::Error {
                    error: GenerationError::RequestFormat {
                        provider: "gemini".to_string(),
                        violations,
                    },
                });
                return Ok(());
            }
        }

        // 6. 构建 headers
        let mut headers = HashMap::new();
        headers.insert(
            "Content-Type".to_string(),
            "application/json".to_string(),
        );
        headers.insert("x-goog-api-key".to_string(), config.api_key.clone());

        // 7. 发送流式请求（带重试逻辑）
        let max_attempts = 3;
        let mut last_error: Option<AgoraError> = None;

        for attempt in 1..=max_attempts {
            let mut stream_resp = match client.stream_post(&url, &request_json, &headers).await {
                Ok(resp) => resp,
                Err(e) => {
                    last_error = Some(e);
                    break;
                }
            };

            // 如果 HTTP 状态码是 200，解析流
            if stream_resp.code == 200 {
                match parse_sse_stream(stream_resp.stream(), on_event).await {
                    Ok(()) => return Ok(()),
                    Err(e) => {
                        last_error = Some(e);
                    }
                }
                break;
            }

            // 可重试状态码
            let retryable = matches!(stream_resp.code, 401 | 429 | 502 | 503 | 504);
            if retryable && attempt < max_attempts {
                on_event(StreamEvent::Retrying {
                    attempt: attempt as i32,
                    max_attempts: max_attempts as i32,
                });
                tokio::time::sleep(std::time::Duration::from_millis(1000 * attempt as u64)).await;
                continue;
            }

            // 不可重试的错误
            let error_body = stream_resp.error_body.unwrap_or_default();
            last_error = Some(match stream_resp.code {
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
                _ => AgoraError::Network {
                    status_code: stream_resp.code as i32,
                    message: format!("HTTP {}: {}", stream_resp.code, error_body),
                },
            });
            break;
        }

        // 如果生成失败，通过回调推送错误事件
        if let Some(err) = last_error {
            on_event(StreamEvent::Error {
                error: GenerationError::Network {
                    status_code: err.status_code().unwrap_or(0),
                    message: err.to_string(),
                },
            });
        }

        Ok(())
    }

    async fn fetch_models(
        &self,
        api_key: &str,
        base_url: Option<&str>,
        client: &AgoraHttpClient,
    ) -> Result<Vec<String>, AgoraError> {
        let effective_base = base_url
            .map(|s| s.trim_end_matches('/'))
            .filter(|s| !s.is_empty())
            .unwrap_or(self.default_base_url());

        let url = if effective_base.contains("/v1") || effective_base.contains("/v1beta") {
            format!("{}/models", effective_base)
        } else {
            format!("{}/v1beta/models", effective_base)
        };

        let mut headers = HashMap::new();
        headers.insert("x-goog-api-key".to_string(), api_key.to_string());

        let body = client.fetch_models(&url, &headers).await?;
        let response: GeminiModelListResponse = serde_json::from_str(&body)?;

        let models: Vec<String> = response
            .models
            .iter()
            .map(|m| m.name.trim_start_matches("models/").to_string())
            .collect();

        Ok(models)
    }
}

// ============================================================
// 消息转换
// ============================================================

/// 将 ChatMessage 列表转换为 GeminiContent 列表
fn build_contents(
    messages: &[ChatMessage],
    config: &ProviderConfig,
) -> Result<Vec<GeminiContent>, AgoraError> {
    let mut contents = Vec::new();

    for msg in messages {
        // 处理 tool_ 消息（模型输出包含 functionCall）
        if msg.id.starts_with("tool_") {
            if let Some(ref tcj) = msg.tool_call_json {
                if !tcj.is_empty() {
                    if let Some(content) = build_tool_call_content(tcj) {
                        contents.push(content);
                        continue;
                    }
                }
            }
        }

        // 处理 result_ 消息（用户提供 functionResponse）
        if msg.id.starts_with("result_") {
            if let Some(ref tcj) = msg.tool_call_json {
                if !tcj.is_empty() {
                    if let Some(content) = build_tool_result_content(tcj) {
                        contents.push(content);
                        continue;
                    }
                }
            }
        }

        // 普通消息
        let role = match msg.participant {
            Participant::User => "user",
            Participant::Model => "model",
            Participant::Error => "model",
        };

        let mut parts = Vec::new();

        if !msg.text.is_empty() {
            parts.push(GeminiPart {
                text: Some(msg.text.clone()),
                inline_data: None,
                function_call: None,
                function_response: None,
                thought_signature: None,
            });
        }

        // 用户消息中的图片
        if config.include_images && msg.participant == Participant::User {
            for image_path in &msg.images {
                if let Ok(part) = build_image_part(image_path) {
                    parts.push(part);
                }
            }
        }

        if parts.is_empty() {
            parts.push(GeminiPart {
                text: Some("[Attachment unavailable]".to_string()),
                inline_data: None,
                function_call: None,
                function_response: None,
                thought_signature: None,
            });
        }

        // 提取 thought_signature 用于 Gemini thinking 连续性
        let signature = msg.tool_call_json.as_ref().and_then(|tcj| {
            serde_json::from_str::<serde_json::Value>(tcj).ok().and_then(|v| {
                v.get("signature")
                    .or_else(|| v.get("thought_signature"))
                    .and_then(|s| s.as_str())
                    .map(|s| s.to_string())
            })
        });

        contents.push(GeminiContent {
            role: role.to_string(),
            parts: if role == "model" && signature.is_some() {
                let mut parts = parts;
                if let Some(ref sig) = signature {
                    if let Some(last) = parts.last_mut() {
                        last.thought_signature = Some(sig.clone());
                    } else if parts.is_empty() {
                        // 空 parts 但有签名：添加一个空 text part 来承载签名
                        parts.push(GeminiPart {
                            text: Some(String::new()),
                            inline_data: None,
                            function_call: None,
                            function_response: None,
                            thought_signature: Some(sig.clone()),
                        });
                    }
                }
                parts
            } else {
                parts
            },
        });
    }

    Ok(contents)
}

/// 构建 tool call 消息的 GeminiContent（模型角色 + functionCall）
fn build_tool_call_content(tool_call_json: &str) -> Option<GeminiContent> {
    let tc: serde_json::Value = serde_json::from_str(tool_call_json).ok()?;
    let id = tc
        .get("toolCallId")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let name = tc.get("toolName").and_then(|v| v.as_str()).unwrap_or("");
    let args_str = tc.get("arguments").and_then(|v| v.as_str()).unwrap_or("{}");
    let args: serde_json::Value =
        serde_json::from_str(args_str).unwrap_or(serde_json::Value::Object(Default::default()));
    let signature = tc.get("signature").and_then(|v| v.as_str());

    let mut fc = serde_json::json!({
        "name": name,
        "args": args,
    });
    if !id.is_empty() {
        fc["id"] = serde_json::Value::String(id.to_string());
    }

    Some(GeminiContent {
        role: "model".to_string(),
        parts: vec![GeminiPart {
            text: None,
            inline_data: None,
            function_call: Some(fc),
            function_response: None,
            thought_signature: signature.map(|s| s.to_string()),
        }],
    })
}

/// 构建 tool result 消息的 GeminiContent（用户角色 + functionResponse）
fn build_tool_result_content(tool_call_json: &str) -> Option<GeminiContent> {
    let tc: serde_json::Value = serde_json::from_str(tool_call_json).ok()?;
    let id = tc
        .get("toolCallId")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let name = tc.get("toolName").and_then(|v| v.as_str()).unwrap_or("");
    let result = tc.get("result").map(|v| v.to_string()).unwrap_or_default();

    let response_body: serde_json::Value =
        serde_json::from_str(&result).unwrap_or(serde_json::json!({"result": result}));

    let mut fr = serde_json::json!({
        "name": name,
        "response": response_body,
    });
    if !id.is_empty() {
        fr["id"] = serde_json::Value::String(id.to_string());
    }

    Some(GeminiContent {
        role: "user".to_string(),
        parts: vec![GeminiPart {
            text: None,
            inline_data: None,
            function_call: None,
            function_response: Some(fr),
        }],
    })
}

/// 从文件路径或 data URL 构建图片 inline_data part
fn build_image_part(path: &str) -> Result<GeminiPart, AgoraError> {
    if path.starts_with("data:") {
        // data URL: 解析 MIME 和 base64
        if let Some(rest) = path.strip_prefix("data:") {
            if let Some(semi) = rest.find(';') {
                let mime_type = &rest[..semi];
                if let Some(b64) = rest.find("base64,") {
                    let data = rest[b64 + 7..].to_string();
                    return Ok(GeminiPart {
                        text: None,
                        inline_data: Some(GeminiInlineData {
                            mime_type: mime_type.to_string(),
                            data,
                        }),
                        function_call: None,
                        function_response: None,
                        thought_signature: None,
                    });
                }
            }
        }
        return Err(AgoraError::Io("Invalid data URL format".to_string()));
    }
    let data = std::fs::read(path)
        .map_err(|e| AgoraError::Io(format!("Failed to read image {}: {}", path, e)))?;
    let b64 = base64::Engine::encode(&base64::engine::general_purpose::STANDARD, &data);
    let mime_type = detect_mime_type(path);

    Ok(GeminiPart {
        text: None,
        inline_data: Some(GeminiInlineData {
            mime_type,
            data: b64,
        }),
        function_call: None,
        function_response: None,
        thought_signature: None,
    })
}

// ============================================================
// Tools 构建
// ============================================================

/// 将 ToolProperty 递归转换为 Gemini JSON Schema 对象
fn tool_property_to_schema(prop: &ToolProperty) -> serde_json::Value {
    let mut schema = serde_json::Map::new();
    schema.insert("type".to_string(), serde_json::Value::String(prop.r#type.clone()));
    schema.insert("description".to_string(), serde_json::Value::String(prop.description.clone()));
    if let Some(ref items) = prop.items {
        schema.insert("items".to_string(), tool_property_to_schema(items));
    }
    serde_json::Value::Object(schema)
}

fn build_tools(config: &ProviderConfig) -> Option<Vec<serde_json::Value>> {
    let mut tools: Vec<serde_json::Value> = Vec::new();

    if config.code_execution_enabled {
        tools.push(serde_json::json!({"code_execution": {}}));
    }
    if config.google_search_enabled {
        tools.push(serde_json::json!({"google_search": {}}));
    }

    // 函数声明
    if let Some(ref tool_defs) = config.tools {
        let declarations: Vec<serde_json::Value> = tool_defs
            .iter()
            .map(|td| {
                let mut props = serde_json::Map::new();
                for (key, prop) in &td.function.parameters.properties {
                    props.insert(key.clone(), tool_property_to_schema(prop));
                }

                let required: Vec<serde_json::Value> = td
                    .function
                    .parameters
                    .required
                    .iter()
                    .map(|r| serde_json::Value::String(r.clone()))
                    .collect();

                serde_json::json!({
                    "name": td.function.name,
                    "description": td.function.description,
                    "parameters": {
                        "type": td.function.parameters.r#type,
                        "properties": props,
                        "required": required,
                    }
                })
            })
            .collect();

        if !declarations.is_empty() {
            tools.push(serde_json::json!({"function_declarations": declarations}));
        }
    }

    if tools.is_empty() {
        None
    } else {
        Some(tools)
    }
}

// ============================================================
// Generation Config 构建
// ============================================================

fn build_generation_config(
    config: &ProviderConfig,
    clean_model: &str,
) -> Option<serde_json::Value> {
    let mut gen_config = serde_json::Map::new();

    if let Some(temp) = config.temperature {
        if let Some(n) = serde_json::Number::from_f64(temp as f64) {
            gen_config.insert("temperature".to_string(), serde_json::Value::Number(n));
        }
    }
    if let Some(max_tokens) = config.max_tokens {
        gen_config.insert(
            "maxOutputTokens".to_string(),
            serde_json::Value::Number(serde_json::Number::from(max_tokens)),
        );
    }
    if let Some(top_p) = config.top_p {
        if let Some(n) = serde_json::Number::from_f64(top_p as f64) {
            gen_config.insert("topP".to_string(), serde_json::Value::Number(n));
        }
    }
    if let Some(fp) = config.frequency_penalty {
        if let Some(n) = serde_json::Number::from_f64(fp as f64) {
            gen_config.insert(
                "frequencyPenalty".to_string(),
                serde_json::Value::Number(n),
            );
        }
    }
    if let Some(pp) = config.presence_penalty {
        if let Some(n) = serde_json::Number::from_f64(pp as f64) {
            gen_config.insert(
                "presencePenalty".to_string(),
                serde_json::Value::Number(n),
            );
        }
    }

    // Thinking config
    if config.thinking_enabled {
        let thinking_config = if clean_model.contains("gemini-3")
            || clean_model.contains("gemini-3.5")
        {
            let level = match config.thinking_level.as_str() {
                "low" => "LOW",
                "high" => "HIGH",
                _ => "MEDIUM",
            };
            serde_json::json!({
                "includeThoughts": true,
                "thinkingLevel": level,
            })
        } else if clean_model.contains("gemini-2.5") {
            let mut tc = serde_json::json!({"includeThoughts": true});
            if config.thinking_budget_enabled {
                tc["thinkingBudget"] = serde_json::json!(config.thinking_budget_tokens);
            }
            tc
        } else if clean_model.contains("thinking-exp") {
            serde_json::json!({"includeThoughts": true})
        } else {
            // 默认对未知模型也启用思考
            serde_json::json!({"includeThoughts": true})
        };

        gen_config.insert("thinkingConfig".to_string(), thinking_config);
    }

    if gen_config.is_empty() {
        None
    } else {
        Some(serde_json::Value::Object(gen_config))
    }
}

// ============================================================
// SSE 流解析
// ============================================================

async fn parse_sse_stream(
    byte_stream: &mut (dyn futures::Stream<Item = Result<bytes::Bytes, reqwest::Error>>
          + Send
          + Unpin),
    on_event: &StreamCallback,
) -> Result<(), AgoraError> {
    let mut raw_buffer = String::new();
    let mut remainder = Vec::new();
    let mut current_thought_signature: Option<String> = None;
    let mut in_thought_block = false;

    while let Some(chunk_result) = byte_stream.next().await {
        match chunk_result {
            Ok(bytes) => {
                append_utf8_safe(&mut raw_buffer, &mut remainder, &bytes);

                while let Some(block) = take_sse_block(&mut raw_buffer) {
                    if let Some(data) = strip_sse_field(&block, "data") {
                        let data = data.trim();
                        if data.is_empty() || data == "[DONE]" {
                            continue;
                        }

                        let response: serde_json::Value = match serde_json::from_str(data) {
                            Ok(v) => v,
                            Err(_) => continue,
                        };

                        // 处理 candidates
                        if let Some(candidates) =
                            response.get("candidates").and_then(|v| v.as_array())
                        {
                            for candidate in candidates {
                                if let Some(parts) = candidate
                                    .get("content")
                                    .and_then(|v| v.get("parts"))
                                    .and_then(|v| v.as_array())
                                {
                                    for part in parts {
                                        let mut is_part_of_thought = false;

                                        // 处理 thought
                                        if let Some(thought) = part.get("thought") {
                                            match thought {
                                                serde_json::Value::String(s) => {
                                                    let title = extract_thought_title(s);
                                                    on_event(StreamEvent::ThoughtChunk {
                                                        thought: s.clone(),
                                                        title,
                                                        signature: current_thought_signature
                                                            .clone(),
                                                    });
                                                    is_part_of_thought = true;
                                                    in_thought_block = true;
                                                }
                                                serde_json::Value::Bool(true) => {
                                                    is_part_of_thought = true;
                                                    in_thought_block = true;
                                                }
                                                _ => {}
                                            }
                                        }

                                        // 处理 reasoning_content
                                        if let Some(rc) = part
                                            .get("reasoning_content")
                                            .and_then(|v| v.as_str())
                                        {
                                            let title = extract_thought_title(rc);
                                            on_event(StreamEvent::ThoughtChunk {
                                                thought: rc.to_string(),
                                                title,
                                                signature: current_thought_signature.clone(),
                                            });
                                            is_part_of_thought = true;
                                            in_thought_block = true;
                                        }

                                        // 处理 thoughtSignature
                                        if let Some(sig) = part
                                            .get("thoughtSignature")
                                            .and_then(|v| v.as_str())
                                        {
                                            current_thought_signature =
                                                Some(sig.to_string());
                                            is_part_of_thought = true;
                                            in_thought_block = true;
                                        }

                                        // 处理 text
                                        if let Some(text) =
                                            part.get("text").and_then(|v| v.as_str())
                                        {
                                            let in_thought =
                                                is_part_of_thought || in_thought_block;
                                            if in_thought {
                                                let title = extract_thought_title(text);
                                                on_event(StreamEvent::ThoughtChunk {
                                                    thought: text.to_string(),
                                                    title,
                                                    signature: current_thought_signature
                                                        .clone(),
                                                });
                                                in_thought_block = false;
                                            } else {
                                                on_event(StreamEvent::TextChunk {
                                                    text: text.to_string(),
                                                });
                                            }
                                        }

                                        // 处理 executable_code
                                        if let Some(ec) = part.get("executableCode") {
                                            let lang = ec
                                                .get("language")
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("");
                                            let code = ec
                                                .get("code")
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("");
                                            on_event(StreamEvent::TextChunk {
                                                text: format!(
                                                    "\n```{}\n{}\n```\n",
                                                    lang, code
                                                ),
                                            });
                                        }

                                        // 处理 code_execution_result
                                        if let Some(cer) =
                                            part.get("codeExecutionResult")
                                        {
                                            let output = cer
                                                .get("output")
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("");
                                            on_event(StreamEvent::TextChunk {
                                                text: format!("\n> Output: {}\n", output),
                                            });
                                        }

                                        // 处理 functionCall
                                        if let Some(fc) = part.get("functionCall") {
                                            let id = fc
                                                .get("id")
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("")
                                                .to_string();
                                            let name = fc
                                                .get("name")
                                                .and_then(|v| v.as_str())
                                                .unwrap_or("")
                                                .to_string();
                                            let args = fc
                                                .get("args")
                                                .map(|v| v.to_string())
                                                .unwrap_or_else(|| "{}".to_string());
                                            let sig = part
                                                .get("thoughtSignature")
                                                .and_then(|v| v.as_str())
                                                .or_else(|| {
                                                    fc.get("thoughtSignature")
                                                        .and_then(|v| v.as_str())
                                                })
                                                .or_else(|| {
                                                    fc.get("thought_signature")
                                                        .and_then(|v| v.as_str())
                                                })
                                                .map(|s| s.to_string())
                                                .or_else(|| {
                                                    current_thought_signature.clone()
                                                });

                                            let call_id = if id.is_empty() {
                                                format!(
                                                    "call_{}",
                                                    uuid::Uuid::new_v4()
                                                )
                                            } else {
                                                id
                                            };

                                            on_event(StreamEvent::ToolCallRequest {
                                                id: call_id,
                                                name,
                                                arguments: args,
                                                signature: sig,
                                            });
                                            current_thought_signature = None;
                                            in_thought_block = false;
                                        }
                                    }
                                }
                            }
                        }

                        // 处理 usageMetadata
                        if let Some(metadata) = response.get("usageMetadata") {
                            let token_count = metadata
                                .get("totalTokenCount")
                                .and_then(|v| v.as_i64())
                                .unwrap_or(0)
                                as i32;
                            let thoughts_token_count = metadata
                                .get("thoughtsTokenCount")
                                .and_then(|v| v.as_i64())
                                .unwrap_or(0)
                                as i32;
                            on_event(StreamEvent::UsageUpdate {
                                token_count,
                                thoughts_token_count,
                            });
                        }
                    }
                }
            }
            Err(e) => {
                return Err(AgoraError::Stream(format!(
                    "Stream read error: {}",
                    e
                )));
            }
        }
    }

    Ok(())
}

// ============================================================
// 辅助函数
// ============================================================

/// 从思考内容中提取标题（**粗体** 或 # 标题）
fn extract_thought_title(content: &str) -> Option<String> {
    // 尝试 **粗体**
    if let Some(start) = content.find("**") {
        let after = &content[start + 2..];
        if let Some(end) = after.find("**") {
            let title = after[..end].trim();
            if !title.is_empty() {
                return Some(title.to_string());
            }
        }
    }
    // 尝试 markdown 标题
    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('#') {
            let heading = trimmed.trim_start_matches('#').trim();
            if !heading.is_empty() {
                return Some(heading.to_string());
            }
        }
    }
    None
}

/// 根据文件扩展名检测 MIME 类型
fn detect_mime_type(path: &str) -> String {
    let lower = path.to_lowercase();
    if lower.ends_with(".png") {
        "image/png"
    } else if lower.ends_with(".jpg") || lower.ends_with(".jpeg") {
        "image/jpeg"
    } else if lower.ends_with(".gif") {
        "image/gif"
    } else if lower.ends_with(".webp") {
        "image/webp"
    } else if lower.ends_with(".bmp") {
        "image/bmp"
    } else if lower.ends_with(".svg") {
        "image/svg+xml"
    } else {
        "image/png"
    }
    .to_string()
}