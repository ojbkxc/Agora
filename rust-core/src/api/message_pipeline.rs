/// 消息准备管道（对应 Kotlin ToolMessages.kt + MessageConverter.kt）
///
/// 提供完整的消息预处理流程：
/// - 去重（按 id）
/// - 状态投影（ERROR/STOPPED → 用户事件）
/// - 工具消息验证（tool_/result_ 轮次校验）
/// - 空消息过滤
/// - 连续同角色合并
/// - 上下文窗口截断
/// - 助手图像投影到最新用户消息

use crate::api::types::{ChatMessage, Participant, MessageStatus};
use std::collections::HashSet;

const TOOL_MSG_PREFIX: &str = "tool_";
const RESULT_MSG_PREFIX: &str = "result_";

// ============================================================
// 主入口
// ============================================================

/// 完整的消息准备管道
pub fn prepare_messages(messages: &[ChatMessage], max_user_messages: i32) -> Vec<ChatMessage> {
    // 1. 去重
    let deduped = distinct_by_id(messages);
    // 2. 状态投影
    let projected = project_generation_statuses_for_api(&deduped);
    // 3. 工具消息验证
    let validated = validate_tool_messages(&projected);
    // 4. 空消息过滤
    let stripped = strip_empty_turns(&validated);
    // 5. 连续同角色合并
    let merged = merge_consecutive_same_role(&stripped);
    // 6. 再次空消息过滤
    let stripped2 = strip_empty_turns(&merged);
    // 7. 上下文截断
    let truncated = limit_context(&stripped2, max_user_messages);
    // 8. 最终空消息过滤
    let stripped3 = strip_empty_turns(&truncated);
    // 9. 再次合并（截断后可能产生连续同角色）
    merge_consecutive_same_role(&stripped3)
}

// ============================================================
// 去重
// ============================================================

fn distinct_by_id(messages: &[ChatMessage]) -> Vec<ChatMessage> {
    let mut seen = HashSet::new();
    let mut result = Vec::new();
    for msg in messages {
        if seen.insert(msg.id.clone()) {
            result.push(msg.clone());
        }
    }
    result
}

// ============================================================
// 状态投影
// ============================================================

/// 将持久化的终态生成行转换为模型可见的状态事件
fn project_generation_statuses_for_api(messages: &[ChatMessage]) -> Vec<ChatMessage> {
    if !messages.iter().any(|m| is_generation_status_message(m)) {
        return messages.to_vec();
    }

    let mut projected: Vec<ChatMessage> = Vec::new();
    let mut pending_statuses: Vec<ChatMessage> = Vec::new();

    for message in messages {
        if is_generation_status_message(message) {
            pending_statuses.push(message.clone());
        } else if !pending_statuses.is_empty()
            && message.participant == Participant::User
            && !is_tool_protocol_message(message)
        {
            let status_text = pending_statuses
                .iter()
                .map(|s| generation_status_event_text(s))
                .collect::<Vec<_>>()
                .join("\n\n");

            let mut merged = message.clone();
            let combined = if status_text.is_empty() {
                message.text.clone()
            } else if message.text.is_empty() {
                status_text
            } else {
                format!("{}\n\n{}", status_text, message.text)
            };
            merged.text = combined;
            projected.push(merged);
            pending_statuses.clear();
        } else {
            // 刷新待处理状态
            for s in &pending_statuses {
                projected.push(as_generation_status_event(s));
            }
            pending_statuses.clear();
            projected.push(message.clone());
        }
    }

    // 刷新剩余待处理状态
    for s in &pending_statuses {
        projected.push(as_generation_status_event(s));
    }

    projected
}

fn is_generation_status_message(msg: &ChatMessage) -> bool {
    !is_tool_protocol_message(msg)
        && (msg.participant == Participant::Error
            || msg.status == MessageStatus::Error
            || msg.status == MessageStatus::Stopped)
}

fn as_generation_status_event(msg: &ChatMessage) -> ChatMessage {
    let mut cloned = msg.clone();
    cloned.text = generation_status_event_text(msg);
    cloned.images = Vec::new();
    cloned.thoughts = None;
    cloned.thought_title = None;
    cloned.token_count = 0;
    cloned.status = MessageStatus::Success;
    cloned.participant = Participant::User;
    cloned.thought_time_ms = None;
    cloned.model_name = None;
    cloned.tool_call_json = None;
    cloned.attachment_meta = None;
    cloned.retry_text = None;
    cloned
}

fn generation_status_event_text(msg: &ChatMessage) -> String {
    let detail = msg.text.trim();
    if msg.participant == Participant::Error || msg.status == MessageStatus::Error {
        let mut text = String::from("[Generation status: ERROR]\n");
        text.push_str("The previous assistant generation failed before completing.");
        if !detail.is_empty() {
            text.push_str("\nDetails:\n");
            text.push_str(detail);
        }
        text
    } else {
        let mut text = String::from("[Generation status: STOPPED]\n");
        text.push_str("The previous assistant generation was stopped before completing.");
        if !detail.is_empty() {
            text.push_str("\nPartial output:\n");
            text.push_str(detail);
        }
        text
    }
}

// ============================================================
// 空消息过滤
// ============================================================

fn strip_empty_turns(messages: &[ChatMessage]) -> Vec<ChatMessage> {
    messages
        .iter()
        .filter(|msg| {
            !msg.text.trim().is_empty()
                || !msg.images.is_empty()
                || is_tool_protocol_message(msg)
                || msg.tool_call_json.is_some()
        })
        .cloned()
        .collect()
}

// ============================================================
// 工具消息检测
// ============================================================

fn is_tool_protocol_message(msg: &ChatMessage) -> bool {
    msg.id.starts_with(TOOL_MSG_PREFIX) || msg.id.starts_with(RESULT_MSG_PREFIX)
}

// ============================================================
// 连续同角色合并
// ============================================================

fn merge_consecutive_same_role(messages: &[ChatMessage]) -> Vec<ChatMessage> {
    if messages.is_empty() {
        return messages.to_vec();
    }
    let mut result: Vec<ChatMessage> = Vec::new();
    let mut i = 0;
    while i < messages.len() {
        let current = &messages[i];
        let is_tool = is_tool_protocol_message(current);
        if is_tool {
            result.push(current.clone());
            i += 1;
            continue;
        }
        // 查找连续的同角色消息
        let mut j = i + 1;
        while j < messages.len() {
            let next = &messages[j];
            if is_tool_protocol_message(next) || next.participant != current.participant {
                break;
            }
            j += 1;
        }
        if j == i + 1 {
            // 无需合并
            result.push(current.clone());
        } else {
            // 合并 messages[i..j]
            let merged_text: String = messages[i..j]
                .iter()
                .map(|m| m.text.as_str())
                .collect::<Vec<_>>()
                .join("\n");
            let merged_images: Vec<String> = messages[i..j]
                .iter()
                .flat_map(|m| m.images.clone())
                .collect();
            let mut merged = current.clone();
            merged.text = merged_text;
            merged.images = merged_images;
            result.push(merged);
        }
        i = j;
    }
    result
}

// ============================================================
// 工具消息验证
// ============================================================

/// 验证并规范化工具调用/结果轮次
fn validate_tool_messages(messages: &[ChatMessage]) -> Vec<ChatMessage> {
    let mut result: Vec<ChatMessage> = Vec::new();
    let mut i = 0;
    while i < messages.len() {
        let msg = &messages[i];
        if msg.id.starts_with(TOOL_MSG_PREFIX) {
            // 收集后续的 result_ 消息
            let mut result_msgs = Vec::new();
            let mut j = i + 1;
            while j < messages.len() && messages[j].id.starts_with(RESULT_MSG_PREFIX) {
                result_msgs.push(messages[j].clone());
                j += 1;
            }
            // 保留工具调用轮次
            result.push(msg.clone());
            result.extend(result_msgs);
            i = j;
        } else if msg.id.starts_with(RESULT_MSG_PREFIX) {
            // 孤立的 result_ 消息，跳过
            i += 1;
        } else {
            result.push(msg.clone());
            i += 1;
        }
    }
    result
}

// ============================================================
// 上下文窗口截断
// ============================================================

/// 限制上下文窗口中的用户消息数量
/// 保留最近的 N 个用户轮次，确保不拆分工具调用轮次
fn limit_context(messages: &[ChatMessage], max_user_messages: i32) -> Vec<ChatMessage> {
    if messages.is_empty() || max_user_messages <= 0 {
        return Vec::new();
    }

    let turn_limit = max_user_messages.max(1) as usize;

    // 将消息分组为轮次（工具调用轮次作为一个单元）
    let mut units: Vec<Vec<ChatMessage>> = Vec::new();
    let mut i = 0;
    while i < messages.len() {
        let msg = &messages[i];
        if msg.id.starts_with(TOOL_MSG_PREFIX) {
            let mut round = vec![msg.clone()];
            i += 1;
            while i < messages.len() && messages[i].id.starts_with(RESULT_MSG_PREFIX) {
                round.push(messages[i].clone());
                i += 1;
            }
            units.push(round);
        } else {
            units.push(vec![msg.clone()]);
            i += 1;
        }
    }

    // 从后往前选择轮次
    let mut selected: Vec<Vec<ChatMessage>> = Vec::new();
    let mut normal_turn_count = 0;

    for unit in units.into_iter().rev() {
        selected.push(unit.clone());
        if unit.len() == 1 && !is_tool_protocol_message(&unit[0]) {
            normal_turn_count += 1;
        }
        if normal_turn_count >= turn_limit {
            break;
        }
    }

    selected.reverse();
    let flattened: Vec<ChatMessage> = selected.into_iter().flatten().collect();

    // 确保以用户消息开头
    let first_user = flattened.iter().position(|m| {
        m.participant == Participant::User && !is_tool_protocol_message(m)
    });

    match first_user {
        Some(idx) => flattened[idx..].to_vec(),
        None => Vec::new(),
    }
}

// ============================================================
// 助手图像投影到最新用户消息
// ============================================================

/// 将助手生成的图像投影到最新用户消息
pub fn project_assistant_images_to_latest_user_message(
    messages: &[ChatMessage],
    include_images: bool,
) -> Vec<ChatMessage> {
    if messages.is_empty() || !include_images {
        return messages.to_vec();
    }

    // 查找最新用户消息索引
    let latest_user_idx = messages
        .iter()
        .rposition(|m| {
            m.participant == Participant::User && !is_tool_protocol_message(m)
        });

    let latest_user_idx = match latest_user_idx {
        Some(idx) => idx,
        None => return messages.to_vec(),
    };

    // 查找助手生成的图像
    let generated_images: Vec<String> = messages[..latest_user_idx]
        .iter()
        .filter(|m| m.participant == Participant::Model && !is_tool_protocol_message(m))
        .filter(|m| !m.images.is_empty())
        .last()
        .map(|m| {
            m.images
                .iter()
                .filter(|img| !img.is_empty())
                .cloned()
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();

    if generated_images.is_empty() {
        return messages.to_vec();
    }

    let mut changed = false;
    let projected: Vec<ChatMessage> = messages
        .iter()
        .enumerate()
        .map(|(idx, msg)| {
            let mut next = msg.clone();
            // 清除助手消息上的图像
            if msg.participant == Participant::Model
                && !is_tool_protocol_message(msg)
                && !msg.images.is_empty()
            {
                next.images = Vec::new();
                changed = true;
            }
            // 将图像添加到最新用户消息
            if idx == latest_user_idx {
                let note = if generated_images.len() == 1 {
                    "[Visual context: the first attached image was generated by the assistant earlier in this conversation.]"
                        .to_string()
                } else {
                    "[Visual context: the first {image_count} attached images were generated by the assistant earlier in this conversation.]"
                        .replace("{image_count}", &generated_images.len().to_string())
                };
                let combined_text = if msg.text.trim().is_empty() {
                    note.to_string()
                } else {
                    format!("{}\n\n{}", note, msg.text)
                };
                next.text = combined_text;
                let mut all_images = generated_images.clone();
                all_images.extend(msg.images.clone());
                next.images = all_images;
                changed = true;
            }
            next
        })
        .collect();

    if changed { projected } else { messages.to_vec() }
}

// ============================================================
// 工具调用 ID 生成
// ============================================================

/// 生成工具调用 ID
pub fn build_tool_call_id(tool_name: &str, arguments: &str) -> String {
    use sha2::{Sha256, Digest};
    let input = format!("{}:{}", tool_name, arguments);
    let hash = Sha256::digest(input.as_bytes());
    let short_hash = hex::encode(&hash[..8]);
    let safe_name: String = tool_name
        .chars()
        .filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-')
        .take(32)
        .collect();
    let safe_name = if safe_name.is_empty() { "tool" } else { &safe_name };
    format!("call_{}_{}", safe_name, short_hash)
}

// ============================================================
// 测试
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_msg(id: &str, text: &str, participant: Participant) -> ChatMessage {
        ChatMessage {
            id: id.to_string(),
            parent_id: None,
            text: text.to_string(),
            images: Vec::new(),
            thoughts: None,
            thought_title: None,
            token_count: 0,
            status: MessageStatus::Success,
            participant,
            timestamp: 0,
            thought_time_ms: None,
            model_name: None,
            tool_call_json: None,
            attachment_meta: None,
            retry_text: None,
        }
    }

    #[test]
    fn test_distinct_by_id() {
        let msgs = vec![
            make_msg("1", "hello", Participant::User),
            make_msg("1", "hello", Participant::User),
            make_msg("2", "world", Participant::Model),
        ];
        let result = distinct_by_id(&msgs);
        assert_eq!(result.len(), 2);
    }

    #[test]
    fn test_strip_empty_turns() {
        let msgs = vec![
            make_msg("1", "", Participant::User),
            make_msg("2", "hello", Participant::User),
        ];
        let result = strip_empty_turns(&msgs);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].id, "2");
    }

    #[test]
    fn test_merge_consecutive_same_role() {
        let msgs = vec![
            make_msg("1", "hello", Participant::User),
            make_msg("2", "world", Participant::User),
            make_msg("3", "reply", Participant::Model),
        ];
        let result = merge_consecutive_same_role(&msgs);
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].text, "hello\nworld");
    }

    #[test]
    fn test_limit_context() {
        let msgs = vec![
            make_msg("1", "first", Participant::User),
            make_msg("2", "reply1", Participant::Model),
            make_msg("3", "second", Participant::User),
            make_msg("4", "reply2", Participant::Model),
        ];
        let result = limit_context(&msgs, 1);
        assert_eq!(result.len(), 2);
        assert!(result[0].text.contains("second"));
    }

    #[test]
    fn test_build_tool_call_id() {
        let id = build_tool_call_id("search", "{\"q\":\"test\"}");
        assert!(id.starts_with("call_search_"));
        assert_eq!(id.len(), 25); // "call_search_" + 16 hex chars
    }

    #[test]
    fn test_prepare_messages_empty() {
        let result = prepare_messages(&[], 20);
        assert!(result.is_empty());
    }

    #[test]
    fn test_prepare_messages_basic() {
        let msgs = vec![
            make_msg("1", "hello", Participant::User),
            make_msg("2", "hi there", Participant::Model),
        ];
        let result = prepare_messages(&msgs, 20);
        assert_eq!(result.len(), 2);
    }
}