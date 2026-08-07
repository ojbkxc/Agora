// HTTP 客户端封装
// 基于 reqwest 的 HTTP/HTTPS 客户端，支持代理、流式响应、安全凭证保护

use std::collections::HashMap;
use std::time::Duration;

use bytes::Bytes;
use futures::Stream;
use reqwest::StatusCode;
use url::Url;

use crate::error::{AgoraError, AgoraResult};
use crate::api::types::*;

// ============================================================
// ProxyConfig — 代理配置
// ============================================================

/// 代理配置
#[derive(Debug, Clone)]
pub struct ProxyConfig {
    /// 代理类型：`http`、`https`、`socks5`
    pub proxy_type: String,
    /// 代理主机
    pub host: String,
    /// 代理端口
    pub port: u16,
    /// 代理用户名（可选）
    pub username: Option<String>,
    /// 代理密码（可选）
    pub password: Option<String>,
}

impl ProxyConfig {
    /// 构建代理 URL（如 `http://user:pass@host:port`）
    pub fn to_url(&self) -> AgoraResult<String> {
        let scheme = match self.proxy_type.as_str() {
            "http" | "https" => "http",
            "socks5" => "socks5",
            _ => {
                return Err(AgoraError::Config(format!(
                    "不支持的代理类型: {}",
                    self.proxy_type
                )));
            }
        };

        let auth = match (&self.username, &self.password) {
            (Some(user), Some(pass)) => format!("{}:{}@", user, pass),
            (Some(user), None) => format!("{}@", user),
            _ => String::new(),
        };

        Ok(format!("{}://{}{}:{}", scheme, auth, self.host, self.port))
    }
}

// ============================================================
// StreamResponse — 流式响应
// ============================================================

/// 流式 HTTP 响应封装
pub struct StreamResponse {
    /// HTTP 状态码
    pub code: u16,
    /// 错误响应体（仅在非 2xx 时填充）
    pub error_body: Option<String>,
    /// 字节流读取器
    reader: Box<dyn Stream<Item = Result<Bytes, reqwest::Error>> + Send + Unpin>,
}

impl StreamResponse {
    /// 创建新的 StreamResponse
    pub fn new(
        code: u16,
        reader: Box<dyn Stream<Item = Result<Bytes, reqwest::Error>> + Send + Unpin>,
    ) -> Self {
        Self {
            code,
            error_body: None,
            reader,
        }
    }

    /// 创建带错误体的 StreamResponse
    pub fn with_error(code: u16, error_body: String) -> Self {
        use futures::stream;
        Self {
            code,
            error_body: Some(error_body),
            reader: Box::new(stream::empty()),
        }
    }

    /// 获取字节流的引用
    pub fn stream(&mut self) -> &mut Box<dyn Stream<Item = Result<Bytes, reqwest::Error>> + Send + Unpin>
    {
        &mut self.reader
    }

    /// 获取字节流的所有权（用于 unfold 模式解析器）
    pub fn into_stream(self) -> Box<dyn Stream<Item = Result<Bytes, reqwest::Error>> + Send + Unpin> {
        self.reader
    }

    /// 关闭流（释放底层连接）
    pub async fn close(&mut self) {
        // Drop 时自动清理，这里显式消费剩余数据
        use futures::StreamExt;
        while let Some(_chunk) = self.reader.next().await {
            // 消费并丢弃
        }
    }

    /// 判断是否为成功响应（无错误体）
    pub fn is_success(&self) -> bool {
        self.error_body.is_none()
    }
}

// ============================================================
// AgoraHttpClient — HTTP 客户端
// ============================================================

/// Agora HTTP 客户端
///
/// 封装 reqwest::Client，提供带安全检查的 HTTP 请求方法。
/// 支持可选的代理配置。
#[derive(Clone)]
pub struct AgoraHttpClient {
    client: reqwest::Client,
}

impl AgoraHttpClient {
    /// 创建新的 HTTP 客户端（无代理）
    ///
    /// 仅用于非 JNI 路径（如内部工具/测试）。JNI 入口应直接调用
    /// `new_with_proxy(None)` 并 match 处理失败，避免 expect panic
    /// 跨越 FFI 边界导致 JVM 立即闪退（panic=abort 不产生 Java 异常栈）。
    pub fn new() -> Self {
        Self::new_with_proxy(None)
            .unwrap_or_else(|e| {
                log::error!("[HTTP] Failed to create default client: {}; falling back to bare builder", e);
                // 最后的兜底：跳过所有自定义配置，仅构建最朴素的 Client。
                // 这条路径理论上不会触发（reqwest 默认 builder 几乎不会失败），
                // 但若 TLS backend 初始化失败等极端情况发生，宁可让请求失败
                // 也不能让进程 abort。
                reqwest::Client::builder()
                    .build()
                    .map(|client| Self { client })
                    .expect("bare reqwest::Client::build() must succeed")
            })
    }

    /// 创建新的 HTTP 客户端
    ///
    /// # 参数
    /// - `proxy_url`: 可选的代理 URL（如 `http://127.0.0.1:7890`、`socks5://...`）
    pub fn new_with_proxy(proxy_url: Option<String>) -> AgoraResult<Self> {
        let mut builder = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(30))
            .timeout(Duration::from_secs(300)) // 5 分钟总超时（含读取）
            .pool_idle_timeout(Duration::from_secs(90))
            .pool_max_idle_per_host(10)
            .tcp_keepalive(Duration::from_secs(30))
            .redirect(reqwest::redirect::Policy::limited(10))
            // 默认 headers
            .user_agent("Agora-RS/0.1.0");

        // 配置代理
        if let Some(ref proxy_url_str) = proxy_url {
            if !proxy_url_str.is_empty() {
                let proxy = reqwest::Proxy::all(proxy_url_str).map_err(|e| {
                    AgoraError::Config(format!("无效的代理 URL '{}': {}", proxy_url_str, e))
                })?;
                builder = builder.proxy(proxy);
            }
        }

        let client = builder.build().map_err(|e| {
            AgoraError::Config(format!("构建 HTTP 客户端失败: {}", e))
        })?;

        Ok(Self { client })
    }

    /// 从 ProxyConfig 创建带代理的客户端
    pub fn with_proxy(config: &ProxyConfig) -> AgoraResult<Self> {
        let proxy_url = Some(config.to_url()?);
        Self::new_with_proxy(proxy_url)
    }

    /// 检查是否应该拒绝明文传输凭证。
    ///
    /// 如果请求目标是非本地主机的 HTTP（非 HTTPS），且 headers 中包含 API key，
    /// 则拒绝发送以防止凭证泄露。
    pub fn guard_cleartext_credentials(
        url: &str,
        headers: &HashMap<String, String>,
    ) -> AgoraResult<()> {
        let parsed = Url::parse(url)
            .map_err(|e| AgoraError::Config(format!("无效的 URL '{}': {}", url, e)))?;

        // 只检查 HTTP（非 HTTPS）
        if parsed.scheme() != "http" {
            return Ok(());
        }

        let host = parsed.host_str().unwrap_or("");

        // 本地主机允许明文 HTTP（开发环境）
        if is_local_host(host) {
            return Ok(());
        }

        // 检查 headers 中是否包含敏感凭证
        let sensitive_keys = ["authorization", "x-api-key", "api-key", "x-goog-api-key"];
        for key in sensitive_keys {
            if headers
                .iter()
                .any(|(k, _)| k.eq_ignore_ascii_case(key))
            {
                return Err(AgoraError::Config(format!(
                    "安全拒绝: 不允许通过非加密 HTTP 向非本地主机 '{}' 发送 API 密钥。请使用 HTTPS。",
                    host
                )));
            }
        }

        Ok(())
    }

    /// 构建带默认 headers 的请求 builder
    fn build_request(
        &self,
        method: reqwest::Method,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> reqwest::RequestBuilder {
        let mut req = self.client.request(method, url);
        for (key, value) in headers {
            req = req.header(key.as_str(), value.as_str());
        }
        req
    }

    /// 检查 HTTP 响应状态码并构造错误
    fn check_status(status: StatusCode, body: &str) -> AgoraResult<()> {
        if status.is_success() {
            return Ok(());
        }

        let status_code = status.as_u16() as i32;

        // 尝试解析 API 错误响应
        if let Ok(err_resp) = serde_json::from_str::<OpenAiErrorResponse>(body) {
            return Err(AgoraError::Network {
                status_code,
                message: format!("[{}] {}", err_resp.error.code.as_deref().unwrap_or("unknown"), err_resp.error.message),
            });
        }

        Err(AgoraError::Network {
            status_code,
            message: body.to_string(),
        })
    }

    /// 流式 POST 请求，返回字节流
    ///
    /// 适用于 SSE 流式生成场景。调用方负责逐块读取和解析。
    pub async fn stream_post(
        &self,
        url: &str,
        json_body: &serde_json::Value,
        headers: &HashMap<String, String>,
    ) -> AgoraResult<StreamResponse> {
        Self::guard_cleartext_credentials(url, headers)?;

        let req = self
            .build_request(reqwest::Method::POST, url, headers)
            .header("accept", "text/event-stream")
            .json(json_body);

        let response = req.send().await.map_err(|e| {
            if e.is_timeout() {
                AgoraError::Timeout
            } else if e.is_connect() {
                AgoraError::Network {
                    status_code: 0,
                    message: format!("连接失败: {}", e),
                }
            } else {
                AgoraError::Network {
                    status_code: 0,
                    message: format!("请求失败: {}", e),
                }
            }
        })?;

        let status = response.status();
        let code = status.as_u16();

        if !status.is_success() {
            // 读取错误响应体，返回 Ok(StreamResponse::with_error) 而非 Err，
            // 使调用方（Provider）能根据 HTTP 状态码实现重试逻辑和友好错误消息。
            let body = response.text().await.unwrap_or_default();
            return Ok(StreamResponse::with_error(code, body));
        }

        let byte_stream = response.bytes_stream();
        let boxed: Box<dyn Stream<Item = Result<Bytes, reqwest::Error>> + Send + Unpin> =
            Box::new(byte_stream);

        Ok(StreamResponse::new(code, boxed))
    }

    /// 非流式 POST 请求，返回完整响应体
    pub async fn post(
        &self,
        url: &str,
        json_body: &serde_json::Value,
        headers: &HashMap<String, String>,
    ) -> AgoraResult<String> {
        Self::guard_cleartext_credentials(url, headers)?;

        let req = self
            .build_request(reqwest::Method::POST, url, headers)
            .json(json_body);

        let response = req.send().await?;
        let status = response.status();
        let body = response.text().await?;

        Self::check_status(status, &body)?;
        Ok(body)
    }

    /// GET 请求
    pub async fn get(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> AgoraResult<String> {
        Self::guard_cleartext_credentials(url, headers)?;

        let req = self.build_request(reqwest::Method::GET, url, headers);

        let response = req.send().await?;
        let status = response.status();
        let body = response.text().await?;

        Self::check_status(status, &body)?;
        Ok(body)
    }

    /// 获取模型列表（GET 请求，返回 JSON 字符串）
    ///
    /// 与 `get` 相同，但语义上用于模型列表查询端点。
    pub async fn fetch_models(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
    ) -> AgoraResult<String> {
        Self::guard_cleartext_credentials(url, headers)?;

        let req = self
            .build_request(reqwest::Method::GET, url, headers)
            .header("accept", "application/json");

        let response = req.send().await?;
        let status = response.status();
        let body = response.text().await?;

        Self::check_status(status, &body)?;
        Ok(body)
    }

    /// 获取底层 reqwest::Client 的引用（高级用途）
    pub fn inner(&self) -> &reqwest::Client {
        &self.client
    }

    /// 简单 GET 请求，返回 `TextResponse { status, body }`
    pub async fn get_text(&self, url: &str) -> AgoraResult<TextResponse> {
        let response = self.client.get(url).send().await?;
        let status = response.status().as_u16();
        let body = response.text().await?;
        Ok(TextResponse { status, body })
    }

    /// 简单 POST JSON 请求，返回 `TextResponse { status, body }`
    pub async fn post_json(
        &self,
        url: &str,
        json_body: &str,
    ) -> AgoraResult<TextResponse> {
        let response = self
            .client
            .post(url)
            .header("Content-Type", "application/json")
            .body(json_body.to_string())
            .send()
            .await?;
        let status = response.status().as_u16();
        let body = response.text().await?;
        Ok(TextResponse { status, body })
    }

    /// POST 请求，自定义 body 和 headers，返回 `TextResponse { status, body }`
    pub async fn post_with_headers(
        &self,
        url: String,
        body: String,
        headers: HashMap<String, String>,
    ) -> AgoraResult<TextResponse> {
        let mut req = self.client.post(&url);
        for (key, value) in &headers {
            req = req.header(key.as_str(), value.as_str());
        }
        let response = req.body(body).send().await?;
        let status = response.status().as_u16();
        let body_text = response.text().await?;
        Ok(TextResponse { status, body: body_text })
    }

    /// POST JSON 并返回解析后的 `serde_json::Value`
    pub async fn post_json_value(
        &self,
        url: &str,
        json_body: &str,
        headers: HashMap<String, String>,
    ) -> AgoraResult<serde_json::Value> {
        let mut req = self.client.post(url);
        for (key, value) in &headers {
            req = req.header(key.as_str(), value.as_str());
        }
        let response = req
            .header("Content-Type", "application/json")
            .body(json_body.to_string())
            .send()
            .await?;
        let status = response.status();
        let body = response.text().await?;
        Self::check_status(status, &body)?;
        serde_json::from_str(&body).map_err(|e| AgoraError::Parse(e.to_string()))
    }
}

/// 简单文本响应
#[derive(Debug, Clone)]
pub struct TextResponse {
    pub status: u16,
    pub body: String,
}

// ============================================================
// 主机检测 — 安全凭证保护
// ============================================================

/// 检测是否为本地/内网主机（允许明文 HTTP）。
///
/// 匹配以下场景：
/// - `localhost` / `127.*` / `::1` — 标准回环地址
/// - `10.*` / `172.16-31.*` / `192.168.*` — RFC 1918 私有地址
/// - `*.ts.net` — Tailscale 网络
/// - `*.local` — mDNS 本地发现
pub fn is_local_host(host: &str) -> bool {
    if host.is_empty() {
        return false;
    }

    let lower = host.to_lowercase();

    // 明确的回环地址
    if lower == "localhost" || lower == "::1" || lower == "[::1]" {
        return true;
    }

    // 127.0.0.0/8
    if lower.starts_with("127.") {
        return true;
    }

    // 10.0.0.0/8
    if lower.starts_with("10.") {
        return true;
    }

    // 172.16.0.0/12 (172.16.x.x ~ 172.31.x.x)
    if lower.starts_with("172.") {
        if let Some(second) = lower.split('.').nth(1) {
            if let Ok(octet) = second.parse::<u8>() {
                if (16..=31).contains(&octet) {
                    return true;
                }
            }
        }
    }

    // 192.168.0.0/16
    if lower.starts_with("192.168.") {
        return true;
    }

    // Tailscale 网络
    if lower.ends_with(".ts.net") {
        return true;
    }

    // mDNS 本地域名
    if lower.ends_with(".local") {
        return true;
    }

    // 链路本地地址 169.254.0.0/16
    if lower.starts_with("169.254.") {
        return true;
    }

    // IPv6 链路本地 fe80::/10
    if lower.starts_with("fe80") {
        return true;
    }

    false
}
