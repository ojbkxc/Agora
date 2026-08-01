/// 请求验证模块（对应 Kotlin RequestFormatValidation.kt）
///
/// 在发送 HTTP 请求前验证请求格式，确保不会发送无效请求到 API。

use crate::api::types::ToolDefinition;
use regex::Regex;

/// 安全的工具名称：仅允许字母数字、下划线、连字符，1-64 字符
pub fn safe_wire_tool_name() -> Regex {
    Regex::new(r"^[A-Za-z0-9_-]{1,64}$").unwrap()
}

/// 安全的工具调用 ID：仅允许字母数字、下划线、连字符，1-128 字符
pub fn safe_wire_tool_call_id() -> Regex {
    Regex::new(r"^[A-Za-z0-9_-]{1,128}$").unwrap()
}

/// 验证工具定义列表，返回违规列表
pub fn validate_tool_definitions(tools: &[ToolDefinition]) -> Vec<String> {
    if tools.is_empty() {
        return Vec::new();
    }
    let mut violations = Vec::new();
    let mut names = std::collections::HashSet::new();
    let name_regex = safe_wire_tool_name();

    for (index, tool) in tools.iter().enumerate() {
        let function = &tool.function;

        if tool.r#type != "function" {
            violations.push(format!("tools[{}].type must be 'function'", index));
        }

        if function.name.trim().is_empty() {
            violations.push(format!("tools[{}].function.name is blank", index));
        } else if !name_regex.is_match(&function.name) {
            violations.push(format!(
                "tools[{}].function.name '{}' is not wire-safe",
                index, function.name
            ));
        } else if !names.insert(function.name.clone()) {
            violations.push(format!("duplicate tool name '{}'", function.name));
        }

        if function.parameters.r#type != "object" {
            violations.push(format!(
                "tool '{}' parameters must be an object",
                function.name
            ));
        }

        let unknown_required: Vec<&str> = function
            .parameters
            .required
            .iter()
            .filter(|r| !function.parameters.properties.contains_key(*r))
            .map(|s| s.as_str())
            .collect();

        if !unknown_required.is_empty() {
            violations.push(format!(
                "tool '{}' requires undefined properties: {:?}",
                function.name, unknown_required
            ));
        }

        for (prop_name, property) in &function.parameters.properties {
            if prop_name.trim().is_empty() {
                violations.push(format!("tool '{}' has a blank property name", function.name));
            }
            if property.r#type.trim().is_empty() {
                violations.push(format!(
                    "tool '{}' property '{}' has no type",
                    function.name, prop_name
                ));
            }
            if property.r#type == "array" && property.items.is_none() {
                violations.push(format!(
                    "tool '{}' array '{}' has no items schema",
                    function.name, prop_name
                ));
            }
        }
    }

    violations
}

/// 验证序列化后的请求体
pub fn require_valid_serialized_request(
    provider: &str,
    body: &str,
    required_string_fields: &[&str],
    required_array_fields: &[&str],
) -> Result<(), RequestFormatError> {
    let root: serde_json::Value = match serde_json::from_str(body) {
        Ok(v) => v,
        Err(_) => {
            return Err(RequestFormatError {
                provider: provider.to_string(),
                violations: vec!["serialized request is not a JSON object".to_string()],
            });
        }
    };

    let obj = match root.as_object() {
        Some(o) => o,
        None => {
            return Err(RequestFormatError {
                provider: provider.to_string(),
                violations: vec!["serialized request is not a JSON object".to_string()],
            });
        }
    };

    let mut violations = Vec::new();

    for field in required_string_fields {
        match obj.get(*field) {
            Some(val) if val.is_string() && !val.as_str().unwrap_or("").is_empty() => {}
            _ => {
                violations.push(format!("serialized '{}' is absent or blank", field));
            }
        }
    }

    for field in required_array_fields {
        match obj.get(*field) {
            Some(val) if val.is_array() && !val.as_array().unwrap_or(&vec![]).is_empty() => {}
            _ => {
                violations.push(format!("serialized '{}' is absent or empty", field));
            }
        }
    }

    if violations.is_empty() {
        Ok(())
    } else {
        Err(RequestFormatError {
            provider: provider.to_string(),
            violations,
        })
    }
}

/// 请求格式错误
#[derive(Debug, Clone)]
pub struct RequestFormatError {
    pub provider: String,
    pub violations: Vec<String>,
}

impl std::fmt::Display for RequestFormatError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{} request validation failed: {}",
            self.provider,
            self.violations.join("; ")
        )
    }
}

impl std::error::Error for RequestFormatError {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::types::{ToolDefinition, ToolFunction, ToolParameters, ToolProperty};
    use std::collections::HashMap;

    #[test]
    fn test_empty_tools_no_violations() {
        let violations = validate_tool_definitions(&[]);
        assert!(violations.is_empty());
    }

    #[test]
    fn test_valid_tool() {
        let mut properties = HashMap::new();
        properties.insert(
            "query".to_string(),
            ToolProperty {
                r#type: "string".to_string(),
                description: "Search query".to_string(),
                items: None,
            },
        );
        let tool = ToolDefinition {
            r#type: "function".to_string(),
            function: ToolFunction {
                name: "search".to_string(),
                description: "Search tool".to_string(),
                parameters: ToolParameters {
                    r#type: "object".to_string(),
                    properties,
                    required: vec!["query".to_string()],
                },
            },
        };
        let violations = validate_tool_definitions(&[tool]);
        assert!(violations.is_empty());
    }

    #[test]
    fn test_blank_name() {
        let tool = ToolDefinition {
            r#type: "function".to_string(),
            function: ToolFunction {
                name: "".to_string(),
                description: "Test".to_string(),
                parameters: ToolParameters {
                    r#type: "object".to_string(),
                    properties: HashMap::new(),
                    required: Vec::new(),
                },
            },
        };
        let violations = validate_tool_definitions(&[tool]);
        assert!(!violations.is_empty());
        assert!(violations[0].contains("blank"));
    }

    #[test]
    fn test_duplicate_name() {
        let make_tool = |name: &str| ToolDefinition {
            r#type: "function".to_string(),
            function: ToolFunction {
                name: name.to_string(),
                description: "Test".to_string(),
                parameters: ToolParameters {
                    r#type: "object".to_string(),
                    properties: HashMap::new(),
                    required: Vec::new(),
                },
            },
        };
        let violations = validate_tool_definitions(&[make_tool("test"), make_tool("test")]);
        assert!(!violations.is_empty());
        assert!(violations.iter().any(|v| v.contains("duplicate")));
    }

    #[test]
    fn test_require_valid_serialized_request() {
        let body = r#"{"model":"gpt-4","messages":[{"role":"user","content":"hi"}]}"#;
        assert!(require_valid_serialized_request("test", body, &["model"], &["messages"]).is_ok());
    }

    #[test]
    fn test_require_valid_serialized_request_missing() {
        let body = r#"{"foo":"bar"}"#;
        let result = require_valid_serialized_request("test", body, &["model"], &["messages"]);
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().violations.len(), 2);
    }

    #[test]
    fn test_wrong_type() {
        let tool = ToolDefinition {
            r#type: "not_function".to_string(),
            function: ToolFunction {
                name: "test".to_string(),
                description: "Test".to_string(),
                parameters: ToolParameters {
                    r#type: "object".to_string(),
                    properties: HashMap::new(),
                    required: Vec::new(),
                },
            },
        };
        let violations = validate_tool_definitions(&[tool]);
        assert!(!violations.is_empty());
        assert!(violations[0].contains("type"));
    }
}