// LLM Provider trait 定义
// 所有 LLM 提供商（OpenAI、Anthropic、Gemini、Ollama 等）实现此 trait

use async_trait::async_trait;

use crate::error::AgoraError;
use crate::api::http_client::AgoraHttpClient;
use crate::api::types::*;

/// LLM 提供商统一接口
///
/// 每个提供商实现此 trait 来提供：
/// - 流式文本生成（`generate_response`）
/// - 模型列表查询（`fetch_models`）
///
/// `generate_response` 返回 `Vec<StreamEvent>`，
/// 即将流式响应完整收集后返回。
/// JNI 层通过回调机制处理流式推送。
#[async_trait]
pub trait LlmProvider: Send + Sync {
    /// 提供商名称（如 `"openai"`, `"anthropic"`, `"gemini"`, `"ollama"`）
    fn name(&self) -> &str;

    /// 该提供商的默认 API 基础 URL
    fn default_base_url(&self) -> &str;

    /// 发送消息并获取流式生成结果
    ///
    /// 内部会构建请求、发送流式 POST、逐块解析 SSE 事件，
    /// 最终将所有 `StreamEvent` 收集为 `Vec` 返回。
    ///
    /// # 参数
    /// - `messages`: 聊天消息历史
    /// - `config`: 提供商配置（API key、model、temperature 等）
    /// - `client`: HTTP 客户端（已配置代理等）
    ///
    /// # 返回
    /// 流式事件序列，包含文本块、思考块、工具调用、用量等。
    async fn generate_response(
        &self,
        messages: &[ChatMessage],
        config: &ProviderConfig,
        client: &AgoraHttpClient,
    ) -> Result<Vec<StreamEvent>, AgoraError>;

    /// 获取可用模型列表
    ///
    /// # 参数
    /// - `api_key`: API 密钥
    /// - `base_url`: 可选的基础 URL（覆盖默认值）
    /// - `client`: HTTP 客户端
    ///
    /// # 返回
    /// 模型 ID 列表
    async fn fetch_models(
        &self,
        api_key: &str,
        base_url: Option<&str>,
        client: &AgoraHttpClient,
    ) -> Result<Vec<String>, AgoraError>;
}
