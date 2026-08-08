// LLM Provider trait 定义
// 所有 LLM 提供商（OpenAI、Anthropic 等）实现此 trait

use async_trait::async_trait;

use crate::error::AgoraError;
use crate::api::http_client::AgoraHttpClient;
use crate::api::types::*;

/// 流式事件回调
///
/// 每个 Provider 在生成过程中通过此回调实时推送事件，
/// JNI 层将其转发到 Kotlin 的 `callbackFlow`。
pub type StreamCallback = Box<dyn Fn(StreamEvent) + Send + Sync>;

/// LLM 提供商统一接口
///
/// 每个提供商实现此 trait 来提供：
/// - 流式文本生成（`generate_response`）
/// - 模型列表查询（`fetch_models`）
///
/// `generate_response` 通过 `on_event` 回调实时推送事件，
/// 而不是先收集再返回，从而实现真正的流式体验。
#[async_trait]
pub trait LlmProvider: Send + Sync {
    /// 提供商名称（如 `"openai"`, `"anthropic"`）
    fn name(&self) -> &str;

    /// 该提供商的默认 API 基础 URL
    fn default_base_url(&self) -> &str;

    /// 发送消息并获取流式生成结果
    ///
    /// 通过 `on_event` 回调实时推送每个 `StreamEvent`，
    /// 包括文本块、思考块、工具调用、用量等。
    ///
    /// # 参数
    /// - `messages`: 聊天消息历史
    /// - `config`: 提供商配置（API key、model、temperature 等）
    /// - `client`: HTTP 客户端（已配置代理等）
    /// - `on_event`: 流式事件回调，每次推送一个事件
    ///
    /// # 返回
    /// `Ok(())` 表示生成成功完成，`Err` 表示整体错误。
    async fn generate_response(
        &self,
        messages: &[ChatMessage],
        config: &ProviderConfig,
        client: &AgoraHttpClient,
        on_event: &StreamCallback,
    ) -> Result<(), AgoraError>;

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