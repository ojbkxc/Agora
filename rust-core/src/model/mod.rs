/// ModelId — 模型标识符（对应 Kotlin model/ModelId.kt）
///
/// "ProviderName:modelId" 格式的类型化包装。
/// 替代散落在各处的 substringBefore(":")/substringAfter(":") 解析。

/// Provider 常量
pub const PROVIDER_OPENAI: &str = "OpenAI";
pub const PROVIDER_ANTHROPIC: &str = "Anthropic";
pub const PROVIDER_UNKNOWN: &str = "Unknown";

/// ModelId — "ProviderName:modelId" 格式的类型化包装
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ModelId {
    provider_name: String,
    model_name: String,
}

impl ModelId {
    /// 创建新的 ModelId
    pub fn new(provider: &str, model_id: &str) -> Self {
        Self {
            provider_name: provider.to_string(),
            model_name: model_id.to_string(),
        }
    }

    /// 解析 "ProviderName:modelId" 字符串。
    /// 对无前缀的遗留 model ID 使用启发式推断。
    pub fn parse(s: &str) -> Self {
        if let Some(idx) = s.find(':') {
            return Self {
                provider_name: s[..idx].to_string(),
                model_name: s[idx + 1..].to_string(),
            };
        }
        // 遗留/无前缀 model ID 启发式推断
        let provider = if s.starts_with("gpt-") || s.starts_with("o1") || s.starts_with("o3") || s.starts_with("o4") {
            PROVIDER_OPENAI
        } else if s.starts_with("claude-") {
            PROVIDER_ANTHROPIC
        } else {
            PROVIDER_UNKNOWN
        };
        Self {
            provider_name: provider.to_string(),
            model_name: s.to_string(),
        }
    }

    /// 获取 provider 名称
    pub fn provider(&self) -> &str {
        &self.provider_name
    }

    /// 获取 model ID
    pub fn model_id(&self) -> &str {
        &self.model_name
    }

    /// API 请求用的裸模型名（去除 "models/" 前缀）
    pub fn api_model_name(&self) -> &str {
        self.model_name.strip_prefix("models/").unwrap_or(&self.model_name)
    }

    /// "ProviderName:modelId" 格式字符串
    pub fn to_prefixed(&self) -> String {
        format!("{}:{}", self.provider_name, self.model_name)
    }
}

impl std::fmt::Display for ModelId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}:{}", self.provider_name, self.model_name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_prefixed() {
        let id = ModelId::parse("OpenAI:gpt-4o");
        assert_eq!(id.provider(), "OpenAI");
        assert_eq!(id.model_id(), "gpt-4o");
    }

    #[test]
    fn test_parse_prefixed_complex() {
        let id = ModelId::parse("OpenAI:gpt-4o");
        assert_eq!(id.provider(), "OpenAI");
        assert_eq!(id.model_id(), "gpt-4o");
    }

    #[test]
    fn test_parse_legacy_gpt() {
        let id = ModelId::parse("gpt-4o");
        assert_eq!(id.provider(), "OpenAI");
        assert_eq!(id.model_id(), "gpt-4o");
    }

    #[test]
    fn test_parse_legacy_o1() {
        let id = ModelId::parse("o1-preview");
        assert_eq!(id.provider(), "OpenAI");
    }

    #[test]
    fn test_parse_legacy_o3() {
        let id = ModelId::parse("o3-mini");
        assert_eq!(id.provider(), "OpenAI");
    }

    #[test]
    fn test_parse_legacy_o4() {
        let id = ModelId::parse("o4-mini");
        assert_eq!(id.provider(), "OpenAI");
    }

    #[test]
    fn test_parse_legacy_claude() {
        let id = ModelId::parse("claude-3-opus");
        assert_eq!(id.provider(), "Anthropic");
        assert_eq!(id.model_id(), "claude-3-opus");
    }

    #[test]
    fn test_parse_legacy_unknown() {
        let id = ModelId::parse("gemini-pro");
        assert_eq!(id.provider(), "Unknown");
        let id2 = ModelId::parse("my-custom-model");
        assert_eq!(id2.provider(), "Unknown");
    }

    #[test]
    fn test_parse_legacy_models_prefix() {
        let id = ModelId::parse("models/my-model");
        assert_eq!(id.provider(), "Unknown");
    }

    #[test]
    fn test_new() {
        let id = ModelId::new("Custom", "my-model");
        assert_eq!(id.provider(), "Custom");
        assert_eq!(id.model_id(), "my-model");
    }

    #[test]
    fn test_to_prefixed() {
        let id = ModelId::new("OpenAI", "gpt-4o");
        assert_eq!(id.to_prefixed(), "OpenAI:gpt-4o");
    }

    #[test]
    fn test_display() {
        let id = ModelId::new("Anthropic", "claude-3");
        assert_eq!(format!("{}", id), "Anthropic:claude-3");
    }

    #[test]
    fn test_api_model_name_no_prefix() {
        let id = ModelId::new("OpenAI", "gpt-4o");
        assert_eq!(id.api_model_name(), "gpt-4o");
    }

    #[test]
    fn test_api_model_name_with_models_prefix() {
        let id = ModelId::new("Unknown", "models/my-model");
        assert_eq!(id.api_model_name(), "my-model");
    }

    #[test]
    fn test_parse_colon_in_model_name() {
        // Model name 本身可能包含冒号（虽然罕见）
        let id = ModelId::parse("OpenAI:gpt-4:0613");
        assert_eq!(id.provider(), "OpenAI");
        assert_eq!(id.model_id(), "gpt-4:0613");
    }

    #[test]
    fn test_equality() {
        let a = ModelId::new("OpenAI", "gpt-4o");
        let b = ModelId::parse("OpenAI:gpt-4o");
        assert_eq!(a, b);
    }

    #[test]
    fn test_clone() {
        let a = ModelId::new("OpenAI", "gpt-4o");
        let b = a.clone();
        assert_eq!(a, b);
    }
}
