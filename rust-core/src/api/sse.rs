// SSE (Server-Sent Events) 流解析器
//
// 参考 cc-switch 的 SSE 解析模式，处理 CJK 字符跨 TCP 分片的边界情况：
//   - append_utf8_safe 使用 std::str::from_utf8 的 valid_up_to / error_len 精确定位
//   - 防御性保护：remainder 超过 3 字节时用 lossy 刷新
//
// 使用 engine_linux01 的 unfold 模式组织流对：
//   - sse_stream_parser：将字节流转换为 SSE 原始块流
//   - parse_sse_json_stream：将字节流转换为 SSE JSON 事件流

use bytes::Bytes;
use futures::stream::{self, Stream, StreamExt};

// ============================================================
// 基础工具函数
// ============================================================

/// 从 SSE 行中提取指定字段的值。
///
/// SSE 协议格式: `field: value` 或 `field:value`（冒号后可有可无空格）。
/// 返回 `Some(value_trimmed)` 或 `None`（行不匹配该字段）。
#[inline]
pub fn strip_sse_field<'a>(line: &'a str, field: &str) -> Option<&'a str> {
    line.strip_prefix(&format!("{field}: "))
        .or_else(|| line.strip_prefix(&format!("{field}:")))
}

/// 从缓冲区中取出第一个完整的 SSE 事件块。
///
/// SSE 事件以连续的空行分隔：`\n\n` 或 `\r\n\r\n`。
/// 优先匹配先出现的分隔符，支持两种格式混合。
#[inline]
pub fn take_sse_block(buffer: &mut String) -> Option<String> {
    let mut best: Option<(usize, usize)> = None;

    for (delimiter, len) in [("\r\n\r\n", 4usize), ("\n\n", 2usize)] {
        if let Some(pos) = buffer.find(delimiter) {
            if best.is_none_or(|(best_pos, _)| pos < best_pos) {
                best = Some((pos, len));
            }
        }
    }

    let (pos, len) = best?;
    let block = buffer[..pos].to_string();
    buffer.drain(..pos + len);
    Some(block)
}

// ============================================================
// append_utf8_safe — 参考 cc-switch 实现
// ============================================================

/// 安全地将新到达的 UTF-8 字节追加到缓冲区，处理跨分片的多字节字符。
///
/// 参考 cc-switch 的实现，使用 `std::str::from_utf8` 的 error 处理机制，
/// 通过 `valid_up_to()` 和 `error_len()` 精确定位有效/无效字节。
///
/// 当 TCP 分片恰好切在一个多字节 UTF-8 字符中间时，
/// 将不完整的字节序列保存到 `remainder` 中，
/// 下次调用时先拼接 `remainder + new_bytes` 再解码。
///
/// # 防御性保护
/// 如果 `remainder` 超过 3 字节（最大不完整 UTF-8 序列），
/// 用 lossy 刷新并重新开始，防止恶意/损坏流导致内存膨胀。
///
/// # 参数
/// - `buffer`: 成功解码的文本追加到这里
/// - `remainder`: 不完整的 UTF-8 字节尾部（会被替换为新的不完整尾部）
/// - `new_bytes`: 新到达的原始字节
pub fn append_utf8_safe(buffer: &mut String, remainder: &mut Vec<u8>, new_bytes: &[u8]) {
    // 构建要解码的字节切片：拼接上次遗留的不完整字节
    let (owned, bytes): (Option<Vec<u8>>, &[u8]) = if remainder.is_empty() {
        (None, new_bytes)
    } else {
        // 防御性保护：remainder 不应超过 3 字节
        if remainder.len() > 3 {
            buffer.push_str(&String::from_utf8_lossy(remainder));
            remainder.clear();
            (None, new_bytes)
        } else {
            let mut combined = std::mem::take(remainder);
            combined.extend_from_slice(new_bytes);
            (Some(combined), &[])
        }
    };
    let input = owned.as_deref().unwrap_or(bytes);

    // 解码循环：消费所有有效 UTF-8，处理无效字节，保留不完整序列
    let mut pos = 0;
    loop {
        match std::str::from_utf8(&input[pos..]) {
            Ok(s) => {
                buffer.push_str(s);
                return;
            }
            Err(e) => {
                let valid_up_to = pos + e.valid_up_to();
                let valid_slice = &input[pos..valid_up_to];
                // 与 cc-switch 一致：使用安全的 from_utf8 + lossy 回退
                match std::str::from_utf8(valid_slice) {
                    Ok(valid) => buffer.push_str(valid),
                    Err(_) => buffer.push_str(&String::from_utf8_lossy(valid_slice)),
                }

                if let Some(invalid_len) = e.error_len() {
                    // 真正无效的字节，发出 U+FFFD 并继续
                    buffer.push('\u{FFFD}');
                    pos = valid_up_to + invalid_len;
                } else {
                    // 不完整的尾部序列，保存到 remainder 供下次使用
                    *remainder = input[valid_up_to..].to_vec();
                    return;
                }
            }
        }
    }
}

// ============================================================
// unfold 模式流解析器 — 参考 engine_linux01
// ============================================================

/// 使用 unfold 模式将字节流转换为 SSE 事件流（原始块）。
///
/// 参考 engine_linux01 的 `sse_stream_parser` 实现，
/// 使用 `stream::unfold` 组织流对处理，自动处理 UTF-8 边界和 SSE 块分隔。
///
/// 内部状态为 (byte_stream, text_buffer, utf8_remainder)，
/// 每次 unfold 迭代尝试从缓冲区提取 SSE 块，不足时从字节流读取。
pub fn sse_stream_parser(
    byte_stream: impl Stream<Item = Result<Bytes, reqwest::Error>> + Send + Unpin + 'static,
) -> impl Stream<Item = Result<String, crate::error::AgoraError>> + Send + Unpin + 'static {
    let initial_state = (byte_stream, String::new(), Vec::<u8>::new());

    stream::unfold(initial_state, move |(mut byte_stream, mut buffer, mut remainder)| async move {
        loop {
            // 尝试从缓冲区提取完整的 SSE 块
            if let Some(block) = take_sse_block(&mut buffer) {
                return Some((Ok(block), (byte_stream, buffer, remainder)));
            }

            // 缓冲区中没有完整块，从字节流读取更多数据
            match byte_stream.next().await {
                Some(Ok(chunk)) => {
                    // 使用 append_utf8_safe 安全处理 UTF-8 边界
                    append_utf8_safe(&mut buffer, &mut remainder, &chunk);
                    // 继续循环，尝试提取块
                }
                Some(Err(e)) => {
                    let err = crate::error::AgoraError::Stream(format!("SSE stream error: {}", e));
                    return Some((Err(err), (byte_stream, buffer, remainder)));
                }
                None => {
                    // 流结束，刷新缓冲区中剩余内容
                    if !buffer.is_empty() {
                        let block = std::mem::take(&mut buffer);
                        return Some((Ok(block), (byte_stream, buffer, remainder)));
                    }
                    return None; // 流完全结束，迭代终止
                }
            }
        }
    })
}

// ============================================================
// SSE 事件类型 & 高级解析器
// ============================================================

/// SSE 解析事件
#[derive(Debug)]
pub enum SseEvent {
    /// 解析后的 JSON 值（来自 `data:` 字段）
    JsonValue(serde_json::Value),
    /// 流结束标记（`data: [DONE]`）
    Done,
    /// 原始 SSE 块（非 data 字段或解析失败）
    RawBlock(String),
}

/// 从 SSE 块中提取 `data:` 字段的拼接值。
fn extract_data_field(block: &str) -> Option<String> {
    let mut data_parts = Vec::new();
    for line in block.lines() {
        if let Some(val) = strip_sse_field(line, "data") {
            data_parts.push(val.to_string());
        }
    }
    if data_parts.is_empty() {
        None
    } else {
        Some(data_parts.join("\n"))
    }
}

/// 将 SSE 块转换为 SseEvent
fn block_to_sse_event(block: &str) -> SseEvent {
    match extract_data_field(block) {
        Some(data) => {
            if data == "[DONE]" {
                SseEvent::Done
            } else {
                match serde_json::from_str(&data) {
                    Ok(value) => SseEvent::JsonValue(value),
                    Err(_) => SseEvent::RawBlock(block.to_string()),
                }
            }
        }
        None => SseEvent::RawBlock(block.to_string()),
    }
}

/// 使用 unfold 模式将字节流解析为 SSE JSON 事件流。
///
/// 结合 `append_utf8_safe` + `take_sse_block` + `stream::unfold`，
/// 自动提取 `data:` 字段并尝试解析 JSON。
///
/// 返回 `SseEvent` 流，调用方通过 `StreamExt::next()` 消费。
pub fn parse_sse_json_stream(
    byte_stream: impl Stream<Item = Result<Bytes, reqwest::Error>> + Send + Unpin + 'static,
) -> impl Stream<Item = Result<SseEvent, crate::error::AgoraError>> + Send + Unpin + 'static {
    let initial_state = (byte_stream, String::new(), Vec::<u8>::new());

    stream::unfold(initial_state, move |(mut byte_stream, mut buffer, mut remainder)| async move {
        loop {
            if let Some(block) = take_sse_block(&mut buffer) {
                let event = block_to_sse_event(&block);
                return Some((Ok(event), (byte_stream, buffer, remainder)));
            }

            match byte_stream.next().await {
                Some(Ok(chunk)) => {
                    append_utf8_safe(&mut buffer, &mut remainder, &chunk);
                }
                Some(Err(e)) => {
                    let err = crate::error::AgoraError::Stream(format!("SSE stream error: {}", e));
                    return Some((Err(err), (byte_stream, buffer, remainder)));
                }
                None => {
                    if !buffer.is_empty() {
                        let block = std::mem::take(&mut buffer);
                        let event = block_to_sse_event(&block);
                        return Some((Ok(event), (byte_stream, buffer, remainder)));
                    }
                    return None;
                }
            }
        }
    })
}

// ============================================================
// 测试
// ============================================================

#[cfg(test)]
mod tests {
    use super::*;

    // ── strip_sse_field 测试 ──

    #[test]
    fn test_strip_sse_field_basic() {
        assert_eq!(strip_sse_field("data: hello", "data"), Some("hello"));
        assert_eq!(strip_sse_field("event: message", "event"), Some("message"));
        assert_eq!(strip_sse_field("id: 123", "id"), Some("123"));
        assert_eq!(strip_sse_field("retry: 5000", "retry"), Some("5000"));
    }

    #[test]
    fn test_strip_sse_field_no_space_after_colon() {
        assert_eq!(strip_sse_field("data:hello", "data"), Some("hello"));
        assert_eq!(strip_sse_field("data:{\"ok\":true}", "data"), Some("{\"ok\":true}"));
    }

    #[test]
    fn test_strip_sse_field_mismatch() {
        assert_eq!(strip_sse_field("event: message", "data"), None);
        assert_eq!(strip_sse_field("data: hello", "event"), None);
    }

    #[test]
    fn test_strip_sse_field_prefix_confusion() {
        // "data" 不应匹配 "data_id"
        assert_eq!(strip_sse_field("data_id: 123", "data"), None);
    }

    #[test]
    fn test_strip_sse_field_colon_in_value() {
        assert_eq!(
            strip_sse_field("data: {\"key\": \"value\"}", "data"),
            Some("{\"key\": \"value\"}")
        );
    }

    #[test]
    fn test_strip_sse_field_cjk() {
        assert_eq!(strip_sse_field("data: 你好世界", "data"), Some("你好世界"));
    }

    // ── take_sse_block 测试 ──

    #[test]
    fn test_take_sse_block_lf() {
        let mut buf = "data: hello\n\ndata: world\n\n".to_string();
        assert_eq!(take_sse_block(&mut buf), Some("data: hello".to_string()));
        assert_eq!(take_sse_block(&mut buf), Some("data: world".to_string()));
        assert_eq!(take_sse_block(&mut buf), None);
    }

    #[test]
    fn test_take_sse_block_crlf() {
        let mut buf = "data: hello\r\n\r\ndata: world\r\n\r\n".to_string();
        assert_eq!(take_sse_block(&mut buf), Some("data: hello".to_string()));
        assert_eq!(take_sse_block(&mut buf), Some("data: world".to_string()));
        assert_eq!(take_sse_block(&mut buf), None);
    }

    #[test]
    fn test_take_sse_block_multiline() {
        let mut buf = "event: message\ndata: line1\ndata: line2\n\n".to_string();
        let block = take_sse_block(&mut buf).unwrap();
        assert!(block.contains("event: message"));
        assert!(block.contains("data: line1"));
        assert!(block.contains("data: line2"));
        assert!(buf.is_empty());
    }

    #[test]
    fn test_take_sse_block_incomplete() {
        let mut buf = "data: hello\n".to_string();
        assert_eq!(take_sse_block(&mut buf), None);
        assert_eq!(buf, "data: hello\n");
    }

    #[test]
    fn test_take_sse_block_empty_buffer() {
        let mut buf = String::new();
        assert_eq!(take_sse_block(&mut buf), None);
    }

    #[test]
    fn test_take_sse_block_json_payload() {
        let mut buf = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n".to_string();
        let block = take_sse_block(&mut buf).unwrap();
        assert!(block.starts_with("data: "));
        assert!(block.contains("\"content\":\"Hi\""));
    }

    #[test]
    fn test_take_sse_block_prefers_crlf_when_both_present() {
        // 同时有 \r\n\r\n 和 \n\n 时，优先匹配先出现的
        let mut buf = "data: first\n\ndata: second\r\n\r\n".to_string();
        assert_eq!(take_sse_block(&mut buf), Some("data: first".to_string()));
        assert_eq!(take_sse_block(&mut buf), Some("data: second".to_string()));
        assert_eq!(take_sse_block(&mut buf), None);
    }

    // ── append_utf8_safe 测试（参考 cc-switch 测试模式）──

    #[test]
    fn ascii_passthrough() {
        let mut buf = String::new();
        let mut rem = Vec::new();
        append_utf8_safe(&mut buf, &mut rem, b"hello world");
        assert_eq!(buf, "hello world");
        assert!(rem.is_empty());
    }

    #[test]
    fn complete_multibyte_in_single_chunk() {
        let mut buf = String::new();
        let mut rem = Vec::new();
        append_utf8_safe(&mut buf, &mut rem, "你好世界".as_bytes());
        assert_eq!(buf, "你好世界");
        assert!(rem.is_empty());
    }

    #[test]
    fn split_multibyte_across_two_chunks() {
        let bytes = "你".as_bytes();
        assert_eq!(bytes.len(), 3);

        let mut buf = String::new();
        let mut rem = Vec::new();

        append_utf8_safe(&mut buf, &mut rem, &bytes[..2]);
        assert_eq!(buf, "");
        assert_eq!(rem.len(), 2);

        append_utf8_safe(&mut buf, &mut rem, &bytes[2..]);
        assert_eq!(buf, "你");
        assert!(rem.is_empty());
    }

    #[test]
    fn split_four_byte_char_across_chunks() {
        let bytes = "😀".as_bytes();
        assert_eq!(bytes.len(), 4);

        let mut buf = String::new();
        let mut rem = Vec::new();

        append_utf8_safe(&mut buf, &mut rem, &bytes[..1]);
        assert_eq!(buf, "");
        assert_eq!(rem.len(), 1);

        append_utf8_safe(&mut buf, &mut rem, &bytes[1..2]);
        assert_eq!(buf, "");
        assert_eq!(rem.len(), 2);

        append_utf8_safe(&mut buf, &mut rem, &bytes[2..3]);
        assert_eq!(buf, "");
        assert_eq!(rem.len(), 3);

        append_utf8_safe(&mut buf, &mut rem, &bytes[3..]);
        assert_eq!(buf, "😀");
        assert!(rem.is_empty());
    }

    #[test]
    fn mixed_ascii_and_split_multibyte() {
        let all = "hi你".as_bytes();
        assert_eq!(all.len(), 5);

        let mut buf = String::new();
        let mut rem = Vec::new();

        append_utf8_safe(&mut buf, &mut rem, &all[..3]);
        assert_eq!(buf, "hi");
        assert_eq!(rem.len(), 1);

        append_utf8_safe(&mut buf, &mut rem, &all[3..]);
        assert_eq!(buf, "hi你");
        assert!(rem.is_empty());
    }

    #[test]
    fn multiple_split_characters_in_sequence() {
        let text = "你好";
        let bytes = text.as_bytes();

        let mut buf = String::new();
        let mut rem = Vec::new();

        append_utf8_safe(&mut buf, &mut rem, &bytes[..4]);
        assert_eq!(buf, "你");
        assert_eq!(rem.len(), 1);

        append_utf8_safe(&mut buf, &mut rem, &bytes[4..]);
        assert_eq!(buf, "你好");
        assert!(rem.is_empty());
    }

    #[test]
    fn empty_chunks_are_harmless() {
        let mut buf = String::new();
        let mut rem = Vec::new();

        append_utf8_safe(&mut buf, &mut rem, b"");
        assert_eq!(buf, "");
        assert!(rem.is_empty());

        append_utf8_safe(&mut buf, &mut rem, b"ok");
        assert_eq!(buf, "ok");

        append_utf8_safe(&mut buf, &mut rem, b"");
        assert_eq!(buf, "ok");
    }

    #[test]
    fn sse_json_with_chinese_split_at_boundary() {
        let json_line = "data: {\"text\":\"你好\"}\n\n";
        let bytes = json_line.as_bytes();

        let ni_start = bytes.windows(3).position(|w| w == "你".as_bytes()).unwrap();
        let split_point = ni_start + 1;

        let mut buf = String::new();
        let mut rem = Vec::new();

        append_utf8_safe(&mut buf, &mut rem, &bytes[..split_point]);
        append_utf8_safe(&mut buf, &mut rem, &bytes[split_point..]);

        assert_eq!(buf, json_line);
        assert!(rem.is_empty());

        let data = strip_sse_field(buf.lines().next().unwrap(), "data").unwrap();
        let parsed: serde_json::Value = serde_json::from_str(data).unwrap();
        assert_eq!(parsed["text"], "你好");
    }

    #[test]
    fn invalid_bytes_flushed_immediately() {
        let mut buf = String::new();
        let mut rem = Vec::new();

        append_utf8_safe(&mut buf, &mut rem, b"hi\xFFok");
        assert!(rem.is_empty(), "remainder should be empty after invalid byte");
        assert!(buf.contains("hi"), "valid prefix must be present");
        assert!(buf.contains("ok"), "valid suffix must be present");
        assert!(buf.contains('\u{FFFD}'), "invalid byte must produce U+FFFD");
    }

    #[test]
    fn invalid_byte_in_slow_path_flushed_immediately() {
        let mut buf = String::new();
        let mut rem = Vec::new();

        // Prime remainder with an incomplete sequence
        append_utf8_safe(&mut buf, &mut rem, &"你".as_bytes()[..1]);
        assert_eq!(rem.len(), 1);

        // Next chunk starts with an invalid byte
        append_utf8_safe(&mut buf, &mut rem, b"\xFFworld");
        assert!(rem.is_empty(), "remainder should be empty");
        assert!(buf.contains("world"), "valid data after invalid byte must appear");
    }

    #[test]
    fn defensive_guard_flushes_oversized_remainder() {
        let mut buf = String::new();
        let mut rem = Vec::new();

        rem.extend_from_slice(b"\x80\x80\x80\x80");
        assert_eq!(rem.len(), 4);

        append_utf8_safe(&mut buf, &mut rem, b"hello");
        assert!(rem.is_empty(), "remainder must be empty after guard flush");
        assert!(buf.contains("hello"), "valid data after guard flush must appear");

        let replacement_count = buf.chars().filter(|&c| c == '\u{FFFD}').count();
        assert_eq!(replacement_count, 4, "each invalid byte should produce one U+FFFD");
    }

    // ── 综合场景测试 ──

    #[test]
    fn test_sse_workflow_complete() {
        let mut raw_buffer = String::new();
        let mut remainder = Vec::new();

        let chunk1 = b"data: {\"text\":\"Hello\"}\n\ndata: {\"text\":\"World\"}\n\n";
        append_utf8_safe(&mut raw_buffer, &mut remainder, chunk1);

        let block1 = take_sse_block(&mut raw_buffer).unwrap();
        assert_eq!(strip_sse_field(&block1, "data"), Some("{\"text\":\"Hello\"}"));

        let block2 = take_sse_block(&mut raw_buffer).unwrap();
        assert_eq!(strip_sse_field(&block2, "data"), Some("{\"text\":\"World\"}"));

        assert_eq!(take_sse_block(&mut raw_buffer), None);
    }

    #[test]
    fn test_sse_workflow_chunked_cjk() {
        let mut raw_buffer = String::new();
        let mut remainder = Vec::new();

        append_utf8_safe(&mut raw_buffer, &mut remainder, b"data: ");
        append_utf8_safe(&mut raw_buffer, &mut remainder, &[0xE4, 0xBD]);
        assert_eq!(take_sse_block(&mut raw_buffer), None);

        append_utf8_safe(&mut raw_buffer, &mut remainder, &[0xA0, 0x0A, 0x0A]);

        let block = take_sse_block(&mut raw_buffer).unwrap();
        assert_eq!(strip_sse_field(&block, "data"), Some("你"));
    }

    // ── extract_data_field 测试 ──

    #[test]
    fn test_extract_data_field_single_line() {
        let block = "data: {\"key\":\"value\"}";
        let result = extract_data_field(block);
        assert_eq!(result, Some("{\"key\":\"value\"}".to_string()));
    }

    #[test]
    fn test_extract_data_field_multiline() {
        let block = "data: line1\ndata: line2";
        let result = extract_data_field(block);
        assert_eq!(result, Some("line1\nline2".to_string()));
    }

    #[test]
    fn test_extract_data_field_no_data() {
        let block = "event: message\nid: 123";
        let result = extract_data_field(block);
        assert_eq!(result, None);
    }

    #[test]
    fn test_extract_data_field_empty() {
        let block = "";
        let result = extract_data_field(block);
        assert_eq!(result, None);
    }

    #[test]
    fn test_extract_data_field_done() {
        let block = "data: [DONE]";
        let result = extract_data_field(block);
        assert_eq!(result, Some("[DONE]".to_string()));
    }

    // ── block_to_sse_event 测试 ──

    #[test]
    fn test_block_to_sse_event_json() {
        let block = "data: {\"key\":\"value\"}";
        match block_to_sse_event(block) {
            SseEvent::JsonValue(v) => assert_eq!(v["key"], "value"),
            _ => panic!("Expected JsonValue"),
        }
    }

    #[test]
    fn test_block_to_sse_event_done() {
        let block = "data: [DONE]";
        match block_to_sse_event(block) {
            SseEvent::Done => {},
            _ => panic!("Expected Done"),
        }
    }

    #[test]
    fn test_block_to_sse_event_raw() {
        let block = "event: message";
        match block_to_sse_event(block) {
            SseEvent::RawBlock(b) => assert_eq!(b, "event: message"),
            _ => panic!("Expected RawBlock"),
        }
    }
}