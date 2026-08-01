/// ToolCallTextParser — 从内容文本中解析工具调用（对应 Kotlin ToolCallTextParser）
///
/// 当 structured tool_calls 不可用时的后备解析方案。
/// 用于 llama.cpp 等自托管服务器将 tool call 放在 content 字段中的情况。
///
/// 识别的形式：
/// - 标签块: `...`
/// - 纯 JSON 对象或数组
/// - `parameters` 字段作为 `arguments` 的别名

use serde_json::Value;

const OPEN_TAG: &str = "<tool_call>";
const CLOSE_TAG: &str = "</tool_call>";

/// 解析结果: (tool_name, arguments_json)
pub type ParsedCall = (String, String);

/// 从内容文本中提取工具调用
pub fn parse(content: &str) -> Vec<ParsedCall> {
    let mut results = Vec::new();
    let mut idx = 0;

    // 优先解析标签块
    while idx < content.len() {
        if let Some(start) = content[idx..].find(OPEN_TAG) {
            let abs_start = idx + start;
            let inner_start = abs_start + OPEN_TAG.len();
            if let Some(end_rel) = content[inner_start..].find(CLOSE_TAG) {
                let inner = content[inner_start..inner_start + end_rel].trim();
                if let Some(parsed) = parse_call_json(inner) {
                    results.push(parsed);
                }
                idx = inner_start + end_rel + CLOSE_TAG.len();
            } else {
                break;
            }
        } else {
            break;
        }
    }

    if !results.is_empty() {
        return results;
    }

    // 后备: 尝试纯 JSON 解析
    let trimmed = content.trim();
    if !trimmed.starts_with('{') && !trimmed.starts_with('[') {
        return results;
    }

    // 尝试单个 JSON 对象
    if let Some(parsed) = parse_call_json(trimmed) {
        results.push(parsed);
        return results;
    }

    // 尝试 JSON 数组
    if trimmed.starts_with('[') {
        if let Ok(array) = serde_json::from_str::<Vec<Value>>(trimmed) {
            for element in &array {
                if let Some(obj) = element.as_object() {
                    if let Some(parsed) = parse_call_json_from_value(&Value::Object(obj.clone())) {
                        results.push(parsed);
                    }
                }
            }
            if !results.is_empty() {
                return results;
            }
        }
    }

    Vec::new()
}

/// 从 JSON 字符串中解析单个工具调用
fn parse_call_json(json_str: &str) -> Option<ParsedCall> {
    let value: Value = serde_json::from_str(json_str).ok()?;
    parse_call_json_from_value(&value)
}

/// 从 JSON Value 中解析单个工具调用
fn parse_call_json_from_value(value: &Value) -> Option<ParsedCall> {
    let obj = value.as_object()?;

    // 获取 name 字段
    let name = string_field(obj, "name")
        .or_else(|| {
            // 尝试 function.name
            obj.get("function")
                .and_then(|f| f.as_object())
                .and_then(|fo| string_field(fo, "name"))
        })?;

    if name.is_empty() {
        return None;
    }

    // 获取 arguments 或 parameters
    let args = obj
        .get("arguments")
        .or_else(|| obj.get("parameters"));
    let arguments = args
        .map(normalize_arguments)
        .unwrap_or_else(|| "{}".to_string());

    Some((name, arguments))
}

fn string_field(obj: &serde_json::Map<String, Value>, key: &str) -> Option<String> {
    obj.get(key).and_then(|v| v.as_str()).map(|s| s.to_string())
}

/// 规范化参数值: 对象/数组保持 JSON 字符串，原始字符串保持原样
fn normalize_arguments(element: &Value) -> String {
    match element {
        Value::Object(_) | Value::Array(_) => serde_json::to_string(element).unwrap_or_else(|_| "{}".to_string()),
        Value::String(s) => s.clone(),
        Value::Null => "{}".to_string(),
        _ => serde_json::to_string(element).unwrap_or_else(|_| "{}".to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_empty_content() {
        assert!(parse("").is_empty());
    }

    #[test]
    fn test_no_tool_calls() {
        assert!(parse("Hello, I'm a helpful assistant.").is_empty());
    }

    #[test]
    fn test_single_tagged_tool_call() {
        let content = r#"Here is the call:
<tool_call>
{"name": "search", "arguments": "{\"query\": \"rust programming\"}"}
</tool_call>
Done."#;
        let result = parse(content);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].0, "search");
        assert_eq!(result[0].1, "{\"query\": \"rust programming\"}");
    }

    #[test]
    fn test_multiple_tagged_tool_calls() {
        let content = r#"<tool_call>
{"name": "search", "arguments": "{\"q\": \"test\"}"}
</tool_call><tool_call>
{"name": "fetch", "arguments": "{\"url\": \"https://example.com\"}"}
</tool_call>"#;
        let result = parse(content);
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].0, "search");
        assert_eq!(result[1].0, "fetch");
    }

    #[test]
    fn test_raw_json_object() {
        let content = r#"{"name": "calculate", "parameters": {"expression": "2+2"}}"#;
        let result = parse(content);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].0, "calculate");
        assert_eq!(result[0].1, "{\"expression\":\"2+2\"}");
    }

    #[test]
    fn test_raw_json_array() {
        let content = r#"[
            {"name": "tool1", "arguments": "{\"a\": 1}"},
            {"name": "tool2", "arguments": "{\"b\": 2}"}
        ]"#;
        let result = parse(content);
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].0, "tool1");
        assert_eq!(result[1].0, "tool2");
    }

    #[test]
    fn test_function_nesting() {
        let content = r#"{"function": {"name": "my_func"}, "parameters": {"key": "value"}}"#;
        let result = parse(content);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].0, "my_func");
    }

    #[test]
    fn test_empty_name_rejected() {
        let content = r#"{"name": "", "arguments": "{}"}"#;
        assert!(parse(content).is_empty());
    }

    #[test]
    fn test_no_name_rejected() {
        let content = r#"{"tool": "search", "arguments": "{}"}"#;
        assert!(parse(content).is_empty());
    }

    #[test]
    fn test_arguments_as_string() {
        let content = r#"{"name": "test", "arguments": "raw string arg"}"#;
        let result = parse(content);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].1, "raw string arg");
    }

    #[test]
    fn test_arguments_as_object() {
        let content = r#"{"name": "test", "arguments": {"key": "value"}}"#;
        let result = parse(content);
        assert_eq!(result.len(), 1);
        // arguments 应该是 JSON 字符串
        let parsed_args: Value = serde_json::from_str(&result[0].1).unwrap();
        assert_eq!(parsed_args, json!({"key": "value"}));
    }

    #[test]
    fn test_no_arguments_defaults() {
        let content = r#"{"name": "no_args_tool"}"#;
        let result = parse(content);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].1, "{}");
    }

    #[test]
    fn test_invalid_json_rejected() {
        let content = r#"{"name": "bad", "arguments": {broken json"#;
        assert!(parse(content).is_empty());
    }

    #[test]
    fn test_prose_not_parsed_as_json() {
        let content = "I can help you with that. Let me explain how it works.";
        assert!(parse(content).is_empty());
    }

    #[test]
    fn test_tag_with_whitespace() {
        let content = "<tool_call>\n  {\"name\": \"whitespace\", \"arguments\": \"{}\"}\n</tool_call>";
        let result = parse(content);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].0, "whitespace");
    }

    #[test]
    fn test_normalize_arguments_null() {
        assert_eq!(normalize_arguments(&Value::Null), "{}");
    }

    #[test]
    fn test_normalize_arguments_number() {
        assert_eq!(normalize_arguments(&json!(42)), "42");
    }

    #[test]
    fn test_normalize_arguments_bool() {
        assert_eq!(normalize_arguments(&json!(true)), "true");
    }

    #[test]
    fn test_prose_with_json_fragment_ignored() {
        let content = r#"You can use this JSON format: {"name": "example"} in your code."#;
        // Should not parse because the whole content is not pure JSON
        assert!(parse(content).is_empty());
    }

    #[test]
    fn test_tagged_blocks_priority_over_raw_json() {
        let content = r#"{"name": "raw", "arguments": "{}"}
<tool_call>
{"name": "tagged", "arguments": "{}"}
</tool_call>"#;
        let result = parse(content);
        // 标签块优先，但 raw JSON 前面没有标签，所以先尝试标签
        // 标签找到了 tagged，返回
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].0, "tagged");
    }
}
