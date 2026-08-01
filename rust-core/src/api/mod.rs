// API 模块入口
// 声明所有子模块并重导出常用类型

pub mod types;
pub mod sse;
pub mod http_client;
pub mod provider;

// Provider 实现（各 LLM 厂商适配层）
pub mod openai;
pub mod anthropic;
pub mod gemini;
pub mod ollama;

// 重导出核心类型，方便外部使用
pub use types::{
    ChatMessage,
    StreamEvent,
    ToolCallRequestData,
    GenerationError,
    ProviderConfig,
    ToolDefinition,
    PendingToolCall,
};

pub use http_client::{
    AgoraHttpClient,
    StreamResponse,
    ProxyConfig,
    TextResponse,
};

pub use provider::LlmProvider;

pub use sse::{
    strip_sse_field,
    take_sse_block,
    append_utf8_safe,
    sse_stream_parser,
    parse_sse_json_stream,
    SseEvent,
};

pub use openai::OpenAiProvider;
pub use anthropic::AnthropicProvider;
pub use gemini::GeminiProvider;
pub use ollama::OllamaProvider;
