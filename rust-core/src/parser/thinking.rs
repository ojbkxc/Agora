/// ThinkingParser — 结构化思考块解析器（对应 Kotlin ThinkingParser）
///
/// 处理 Anthropic 风格的 thinking delta 事件：
/// - thinking_delta: 累积思考文本
/// - signature_delta: 记录签名
/// - content_block_start: 提取标题

use serde_json::Value;

/// ThinkingParser — 解析结构化思考块
pub struct ThinkingParser {
    thoughts: String,
    title: Option<String>,
    signature: Option<String>,
}

impl ThinkingParser {
    pub fn new() -> Self {
        Self {
            thoughts: String::new(),
            title: None,
            signature: None,
        }
    }

    /// 处理一个思考 delta 事件
    ///
    /// delta_type: 事件类型（如 "thinking_delta", "signature_delta"）
    /// delta_value: 事件数据 JSON
    pub fn feed_delta(&mut self, delta_type: &str, delta_value: &Value) {
        match delta_type {
            "thinking_delta" => {
                if let Some(text) = delta_value.get("text").and_then(|v| v.as_str()) {
                    self.thoughts.push_str(text);
                }
                // 某些实现把思考内容放在 "thinking" 字段
                if self.thoughts.is_empty() {
                    if let Some(thinking) = delta_value.get("thinking").and_then(|v| v.as_str()) {
                        self.thoughts.push_str(thinking);
                    }
                }
                // 尝试提取 title
                if self.title.is_none() {
                    if let Some(title) = delta_value.get("title").and_then(|v| v.as_str()) {
                        if !title.is_empty() {
                            self.title = Some(title.to_string());
                        }
                    }
                }
            }
            "signature_delta" => {
                if let Some(sig) = delta_value.get("signature").and_then(|v| v.as_str()) {
                    self.signature = Some(sig.to_string());
                }
            }
            "content_block_start" => {
                // Anthropic content_block_start 中可能包含 thinking block 的信息
                if let Some(cb) = delta_value.get("content_block") {
                    if let Some(cb_type) = cb.get("type").and_then(|v| v.as_str()) {
                        if cb_type == "thinking" {
                            if let Some(thinking) = cb.get("thinking").and_then(|v| v.as_str()) {
                                self.thoughts.push_str(thinking);
                            }
                        }
                    }
                    // 标题
                    if self.title.is_none() {
                        if let Some(title) = cb.get("title").and_then(|v| v.as_str()) {
                            if !title.is_empty() {
                                self.title = Some(title.to_string());
                            }
                        }
                    }
                    // 签名
                    if self.signature.is_none() {
                        if let Some(sig) = cb.get("signature").and_then(|v| v.as_str()) {
                            if !sig.is_empty() {
                                self.signature = Some(sig.to_string());
                            }
                        }
                    }
                }
            }
            _ => {
                // 未知类型，忽略
            }
        }
    }

    /// 冲洗并返回 (thoughts, title, signature)
    pub fn flush(&self) -> (String, Option<String>, Option<String>) {
        (
            self.thoughts.clone(),
            self.title.clone(),
            self.signature.clone(),
        )
    }

    /// 重置状态（用于下一个思考块）
    pub fn reset(&mut self) {
        self.thoughts.clear();
        self.title = None;
        self.signature = None;
    }

    /// 获取当前已累积的思考文本
    pub fn thoughts(&self) -> &str {
        &self.thoughts
    }

    /// 获取当前标题
    pub fn title(&self) -> Option<&str> {
        self.title.as_deref()
    }

    /// 获取当前签名
    pub fn signature(&self) -> Option<&str> {
        self.signature.as_deref()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_empty_parser() {
        let parser = ThinkingParser::new();
        let (thoughts, title, sig) = parser.flush();
        assert_eq!(thoughts, "");
        assert!(title.is_none());
        assert!(sig.is_none());
    }

    #[test]
    fn test_thinking_delta_text() {
        let mut parser = ThinkingParser::new();
        parser.feed_delta("thinking_delta", &json!({"text": "Hello "}));
        parser.feed_delta("thinking_delta", &json!({"text": "world"}));
        let (thoughts, _, _) = parser.flush();
        assert_eq!(thoughts, "Hello world");
    }

    #[test]
    fn test_thinking_delta_thinking_field() {
        let mut parser = ThinkingParser::new();
        parser.feed_delta("thinking_delta", &json!({"thinking": "reasoning text"}));
        let (thoughts, _, _) = parser.flush();
        assert_eq!(thoughts, "reasoning text");
    }

    #[test]
    fn test_signature_delta() {
        let mut parser = ThinkingParser::new();
        parser.feed_delta("thinking_delta", &json!({"text": "thought"}));
        parser.feed_delta("signature_delta", &json!({"signature": "abc123"}));
        let (thoughts, _, sig) = parser.flush();
        assert_eq!(thoughts, "thought");
        assert_eq!(sig, Some("abc123".to_string()));
    }

    #[test]
    fn test_title_from_thinking_delta() {
        let mut parser = ThinkingParser::new();
        parser.feed_delta("thinking_delta", &json!({"text": "thought", "title": "Analysis"}));
        let (_, title, _) = parser.flush();
        assert_eq!(title, Some("Analysis".to_string()));
    }

    #[test]
    fn test_content_block_start_thinking() {
        let mut parser = ThinkingParser::new();
        parser.feed_delta("content_block_start", &json!({
            "content_block": {
                "type": "thinking",
                "thinking": "initial thought"
            }
        }));
        let (thoughts, _, _) = parser.flush();
        assert_eq!(thoughts, "initial thought");
    }

    #[test]
    fn test_content_block_start_with_signature() {
        let mut parser = ThinkingParser::new();
        parser.feed_delta("content_block_start", &json!({
            "content_block": {
                "type": "thinking",
                "thinking": "thought",
                "signature": "sig_value",
                "title": "My Title"
            }
        }));
        let (thoughts, title, sig) = parser.flush();
        assert_eq!(thoughts, "thought");
        assert_eq!(title, Some("My Title".to_string()));
        assert_eq!(sig, Some("sig_value".to_string()));
    }

    #[test]
    fn test_unknown_delta_type_ignored() {
        let mut parser = ThinkingParser::new();
        parser.feed_delta("text_delta", &json!({"text": "not thinking"}));
        let (thoughts, _, _) = parser.flush();
        assert_eq!(thoughts, "");
    }

    #[test]
    fn test_reset() {
        let mut parser = ThinkingParser::new();
        parser.feed_delta("thinking_delta", &json!({"text": "old thought"}));
        parser.feed_delta("signature_delta", &json!({"signature": "old_sig"}));
        parser.reset();

        let (thoughts, title, sig) = parser.flush();
        assert_eq!(thoughts, "");
        assert!(title.is_none());
        assert!(sig.is_none());
    }

    #[test]
    fn test_reset_and_reuse() {
        let mut parser = ThinkingParser::new();

        // 第一轮
        parser.feed_delta("thinking_delta", &json!({"text": "first"}));
        parser.feed_delta("signature_delta", &json!({"signature": "sig1"}));
        let (t1, _, s1) = parser.flush();
        assert_eq!(t1, "first");
        assert_eq!(s1, Some("sig1".to_string()));

        // 重置后第二轮
        parser.reset();
        parser.feed_delta("thinking_delta", &json!({"text": "second"}));
        parser.feed_delta("signature_delta", &json!({"signature": "sig2"}));
        let (t2, _, s2) = parser.flush();
        assert_eq!(t2, "second");
        assert_eq!(s2, Some("sig2".to_string()));
    }

    #[test]
    fn test_accessors() {
        let mut parser = ThinkingParser::new();
        assert_eq!(parser.thoughts(), "");
        assert!(parser.title().is_none());
        assert!(parser.signature().is_none());

        parser.feed_delta("thinking_delta", &json!({"text": "my thought", "title": "MyTitle"}));
        parser.feed_delta("signature_delta", &json!({"signature": "sig"}));

        assert_eq!(parser.thoughts(), "my thought");
        assert_eq!(parser.title(), Some("MyTitle"));
        assert_eq!(parser.signature(), Some("sig"));
    }

    #[test]
    fn test_multiple_thinking_deltas_accumulate() {
        let mut parser = ThinkingParser::new();
        for i in 0..100 {
            parser.feed_delta("thinking_delta", &json!({"text": format!("chunk{} ", i)}));
        }
        let (thoughts, _, _) = parser.flush();
        assert!(thoughts.starts_with("chunk0 "));
        assert!(thoughts.ends_with("chunk99 "));
        assert_eq!(thoughts.len(), "chunk0 ".len() * 100);
    }

    #[test]
    fn test_signature_overwritten() {
        let mut parser = ThinkingParser::new();
        parser.feed_delta("signature_delta", &json!({"signature": "first"}));
        parser.feed_delta("signature_delta", &json!({"signature": "second"}));
        let (_, _, sig) = parser.flush();
        assert_eq!(sig, Some("second".to_string()));
    }
}
