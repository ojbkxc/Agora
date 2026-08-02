use crate::api::http_client::AgoraHttpClient;
use crate::crypto::conch::ConchSession;
use crate::error::{AgoraError, AgoraResult};
use serde::{Deserialize, Serialize};

/// 文件读取结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileReadResult {
    pub content: String,
    pub lines: i32,
    pub total_lines: i32,
    #[serde(default)]
    pub error: Option<String>,
}

/// Grep 匹配结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GrepMatch {
    pub path: String,
    pub line: i32,
    pub content: String,
}

/// Shell 客户端（对应 Kotlin ShellClient）
pub struct ShellClient {
    server_url: String,
    api_key: String,
    session: ConchSession,
    http_client: AgoraHttpClient,
    last_error: Option<String>,
}

impl ShellClient {
    /// 创建新的 ShellClient
    pub fn new(server_url: String, api_key: String, cached_public_key: String) -> Self {
        let mut session = ConchSession::new();

        // 尝试使用缓存的公钥
        if !cached_public_key.is_empty() {
            if session.set_server_public_key(&cached_public_key).is_err() {
                // 缓存无效，后续会重新获取
            }
        }

        Self {
            server_url,
            api_key,
            session,
            http_client: AgoraHttpClient::new(),
            last_error: None,
        }
    }

    /// 获取服务器公钥
    pub async fn fetch_public_key(&mut self) -> AgoraResult<bool> {
        if self.session.server_public_key().is_some() {
            return Ok(true);
        }

        if self.api_key.is_empty() {
            self.last_error = Some(
                "Conch authentication is disabled locally; no public-key exchange is needed"
                    .to_string(),
            );
            return Ok(false);
        }

        let url = format!("{}/public-key", self.server_url);
        match self.http_client.get_text(&url).await {
            Ok(resp) => {
                if resp.status != 200 {
                    let detail = if resp.body.len() > 240 {
                        &resp.body[..resp.body.floor_char_boundary(240)]
                    } else {
                        resp.body.as_str()
                    };
                    self.last_error = Some(format!(
                        "Conch at {} returned HTTP {}: {}",
                        self.server_url, resp.status, detail
                    ));
                    return Ok(false);
                }

                let json: serde_json::Value = serde_json::from_str(&resp.body)
                    .map_err(|e| AgoraError::Parse(e.to_string()))?;

                let pub_key_str = json
                    .get("public_key")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| {
                        AgoraError::Parse("Missing public_key in response".to_string())
                    })?;
                let nonce = json
                    .get("nonce")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| AgoraError::Parse("Missing nonce in response".to_string()))?;
                let sig = json
                    .get("signature")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| {
                        AgoraError::Parse("Missing signature in response".to_string())
                    })?;

                // 验证签名
                if !ConchSession::verify_public_key_signature(
                    &self.api_key,
                    pub_key_str,
                    nonce,
                    sig,
                ) {
                    self.last_error = Some(format!(
                        "Conch authentication failed at {}: the public-key signature does not match the configured API key",
                        self.server_url
                    ));
                    return Ok(false);
                }

                self.session.set_server_public_key(pub_key_str)?;
                self.last_error = None;
                Ok(true)
            }
            Err(e) => {
                self.last_error = Some(describe_conch_request_failure(
                    &self.server_url,
                    "public-key request",
                    &e,
                ));
                Ok(false)
            }
        }
    }

    /// 获取最后的错误信息
    pub fn last_error(&self) -> Option<&str> {
        self.last_error.as_deref()
    }

    /// 获取服务器公钥（base64url 编码）
    pub fn get_server_public_key_base64(&self) -> Option<String> {
        self.session
            .server_public_key()
            .map(crate::crypto::ecdh::encode_public_key)
    }

    /// 执行命令
    pub async fn execute_command(
        &mut self,
        command: &str,
        timeout_ms: i32,
        workdir: &str,
    ) -> AgoraResult<String> {
        let body = build_command_json(command, timeout_ms, workdir);
        self.encrypted_post("/execute", &body).await
    }

    /// 读取文件
    pub async fn file_read(
        &mut self,
        path: &str,
        offset: i64,
        limit: i64,
    ) -> AgoraResult<FileReadResult> {
        let limit_val = if limit > 0 { limit } else { 1_048_576 };
        let payload = serde_json::json!({
            "path": path,
            "offset": offset,
            "limit": limit_val
        })
        .to_string();
        let json_str = self.encrypted_post("/file/read", &payload).await?;
        let json: serde_json::Value =
            serde_json::from_str(&json_str).map_err(|e| AgoraError::Parse(e.to_string()))?;

        if let Some(error) = json.get("error").and_then(|v| v.as_str()) {
            return Ok(FileReadResult {
                content: String::new(),
                lines: 0,
                total_lines: 0,
                error: Some(error.to_string()),
            });
        }

        Ok(FileReadResult {
            content: json
                .get("content")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            lines: json
                .get("lines")
                .and_then(|v| v.as_i64())
                .unwrap_or(0) as i32,
            total_lines: json
                .get("totalLines")
                .and_then(|v| v.as_i64())
                .unwrap_or(0) as i32,
            error: None,
        })
    }

    /// 写入文件
    pub async fn file_write(
        &mut self,
        path: &str,
        content: &str,
    ) -> AgoraResult<Option<String>> {
        let payload = serde_json::json!({
            "path": path,
            "content": content
        })
        .to_string();
        let json_str = self.encrypted_post("/file/write", &payload).await?;
        let json: serde_json::Value =
            serde_json::from_str(&json_str).map_err(|e| AgoraError::Parse(e.to_string()))?;

        Ok(json
            .get("error")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string()))
    }

    /// 编辑文件
    pub async fn file_edit(
        &mut self,
        path: &str,
        old_str: &str,
        new_str: &str,
    ) -> AgoraResult<Option<String>> {
        let payload = serde_json::json!({
            "path": path,
            "old_str": old_str,
            "new_str": new_str
        })
        .to_string();
        let json_str = self.encrypted_post("/file/edit", &payload).await?;
        let json: serde_json::Value =
            serde_json::from_str(&json_str).map_err(|e| AgoraError::Parse(e.to_string()))?;

        Ok(json
            .get("error")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string()))
    }

    /// Glob 文件搜索
    pub async fn file_glob(
        &mut self,
        pattern: &str,
        base_path: &str,
        depth: Option<i32>,
    ) -> AgoraResult<Vec<String>> {
        let mut params = serde_json::json!({
            "pattern": pattern
        });
        if !base_path.is_empty() {
            params["path"] = serde_json::Value::String(base_path.to_string());
        }
        if let Some(d) = depth {
            params["depth"] = serde_json::Value::Number(d.into());
        }
        let payload = params.to_string();
        let json_str = self.encrypted_post("/file/glob", &payload).await?;
        let json: serde_json::Value =
            serde_json::from_str(&json_str).map_err(|e| AgoraError::Parse(e.to_string()))?;

        if let Some(error) = json.get("error").and_then(|v| v.as_str()) {
            return Err(AgoraError::Shell(error.to_string()));
        }

        let files = json
            .get("files")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|v| v.as_str().map(|s| s.to_string()))
                    .collect()
            })
            .unwrap_or_default();

        Ok(files)
    }

    /// Grep 文件内容搜索
    pub async fn file_grep(
        &mut self,
        pattern: &str,
        base_path: &str,
        file_glob: &str,
    ) -> AgoraResult<Vec<GrepMatch>> {
        let mut params = serde_json::json!({
            "pattern": pattern
        });
        if !base_path.is_empty() {
            params["path"] = serde_json::Value::String(base_path.to_string());
        }
        if !file_glob.is_empty() {
            params["glob"] = serde_json::Value::String(file_glob.to_string());
        }
        let payload = params.to_string();
        let json_str = self.encrypted_post("/file/grep", &payload).await?;
        let json: serde_json::Value =
            serde_json::from_str(&json_str).map_err(|e| AgoraError::Parse(e.to_string()))?;

        if let Some(error) = json.get("error").and_then(|v| v.as_str()) {
            return Err(AgoraError::Shell(error.to_string()));
        }

        let matches = json
            .get("matches")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|item| {
                        Some(GrepMatch {
                            path: item
                                .get("path")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .to_string(),
                            line: item
                                .get("line")
                                .and_then(|v| v.as_i64())
                                .unwrap_or(0) as i32,
                            content: item
                                .get("content")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .to_string(),
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();

        Ok(matches)
    }

    /// 启动后台任务
    pub async fn start_job(
        &mut self,
        command: &str,
        timeout_ms: i32,
        workdir: &str,
    ) -> AgoraResult<String> {
        let body = build_command_json(command, timeout_ms, workdir);
        self.encrypted_post("/jobs/start", &body).await
    }

    /// 列出后台任务
    pub async fn list_jobs(&mut self) -> AgoraResult<String> {
        self.encrypted_post("/jobs/list", "{}").await
    }

    /// 获取后台任务状态
    pub async fn get_job(&mut self, job_id: &str) -> AgoraResult<String> {
        let payload = serde_json::json!({ "job_id": job_id }).to_string();
        self.encrypted_post("/jobs/get", &payload).await
    }

    /// 停止后台任务
    pub async fn stop_job(&mut self, job_id: &str) -> AgoraResult<String> {
        let payload = serde_json::json!({ "job_id": job_id }).to_string();
        self.encrypted_post("/jobs/stop", &payload).await
    }

    /// 加密 POST 请求（对应 Kotlin ShellClient.encryptedPost）
    async fn encrypted_post(&mut self, path: &str, payload: &str) -> AgoraResult<String> {
        if self.api_key.is_empty() {
            // 无加密模式
            let url = format!("{}{}", self.server_url, path);
            let resp = self
                .http_client
                .post_json(&url, payload)
                .await
                .map_err(|e| {
                    AgoraError::Shell(describe_conch_request_failure(
                        &self.server_url,
                        &format!("{} request", path),
                        &e,
                    ))
                })?;

            if resp.status != 200 {
                return Err(AgoraError::Shell(format!(
                    "Conch at {} returned HTTP {}: {}",
                    self.server_url,
                    resp.status,
                    truncate(&resp.body, 240)
                )));
            }
            return Ok(resp.body);
        }

        // 加密模式：确保已获取服务器公钥
        if self.session.server_public_key().is_none() && !self.fetch_public_key().await? {
            return Err(AgoraError::Shell(
                self.last_error
                    .clone()
                    .unwrap_or_else(|| "Failed to fetch server public key".to_string()),
            ));
        }

        // 准备加密请求
        let (encrypted_body, headers) =
            self.session
                .prepare_request(&self.api_key, "POST", path, payload)?;

        let url = format!("{}{}", self.server_url, path);
        let resp = self
            .http_client
            .post_with_headers(url, encrypted_body, headers)
            .await
            .map_err(|e| {
                AgoraError::Shell(describe_conch_request_failure(
                    &self.server_url,
                    &format!("{} request", path),
                    &e,
                ))
            })?;

        if resp.status != 200 {
            return Err(AgoraError::Shell(format!(
                "Conch at {} returned HTTP {}: {}",
                self.server_url,
                resp.status,
                truncate(&resp.body, 240)
            )));
        }

        // 解密响应
        self.session.decrypt_response(&resp.body)
    }
}

/// 构建命令 JSON
fn build_command_json(command: &str, timeout_ms: i32, workdir: &str) -> String {
    let mut map = serde_json::json!({
        "command": command,
        "timeout_ms": timeout_ms,
    });
    if !workdir.is_empty() {
        map["workdir"] = serde_json::Value::String(workdir.to_string());
    }
    map.to_string()
}

/// 截断字符串
fn truncate(s: &str, max_len: usize) -> &str {
    if s.len() > max_len {
        &s[..s.floor_char_boundary(max_len)]
    } else {
        s
    }
}

/// 描述 Conch 连接失败原因
fn describe_conch_request_failure(
    server_url: &str,
    operation: &str,
    error: &AgoraError,
) -> String {
    match error {
        AgoraError::Network { status_code, message } => {
            if *status_code == 0 {
                format!(
                    "Cannot connect to Conch at {}: {}",
                    server_url, message
                )
            } else {
                format!(
                    "Conch {} to {} failed: HTTP {} - {}",
                    operation, server_url, status_code, message
                )
            }
        }
        AgoraError::Timeout => {
            format!("Conch {} timed out at {}", operation, server_url)
        }
        _ => {
            format!(
                "Conch {} to {} failed: {}",
                operation, server_url, error
            )
        }
    }
}
