/// 增量式思考标签词法分析器（对应 Kotlin IncrementalThinkingParser）
///
/// 在 Markdown 渲染之前操作，追踪 fenced 和 inline code：
/// 代码块中出现的看起来像标记的文本是字面可见文本，不是协议分隔符。
/// 支持所有可能的 chunk 边界，只保留可能成为标记的后缀。

/// 代码状态
#[derive(Debug, Clone, PartialEq)]
enum CodeState {
    None,
    Inline { ticks: usize },
    Fence { character: char, length: usize },
}

/// 标记定义
#[derive(Debug, Clone)]
struct Marker {
    start: &'static str,
    ends: Vec<&'static str>,
}

fn build_markers() -> Vec<Marker> {
    let channel_ends: Vec<&'static str> = vec![
        "<|end|>",
        "<|channel|>final<|message|>",
        "<|start|>assistant<|channel|>final<|message|>",
    ];
    vec![
        Marker { start: "<think>", ends: vec!["</think>"] },
        Marker { start: "<thinking>", ends: vec!["</thinking>"] },
        Marker { start: "<reasoning>", ends: vec!["</reasoning>"] },
        Marker { start: "<analysis>", ends: vec!["</analysis>"] },
        Marker { start: "<thought>", ends: vec!["</thought>"] },
        Marker { start: "<|channel|>thought<|message|>", ends: channel_ends.clone() },
        Marker { start: "<|channel|>reasoning<|message|>", ends: channel_ends.clone() },
        Marker { start: "<|channel|>analysis<|message|>", ends: channel_ends.clone() },
        Marker { start: "<|start|>assistant<|channel|>thought<|message|>", ends: channel_ends.clone() },
        Marker { start: "<|start|>assistant<|channel|>reasoning<|message|>", ends: channel_ends.clone() },
        Marker { start: "<|start|>assistant<|channel|>analysis<|message|>", ends: channel_ends.clone() },
        Marker { start: "<|channel>thought\n", ends: vec!["<channel|>"] },
        Marker { start: "<|channel>analysis\n", ends: vec!["<channel|>"] },
    ]
}

#[derive(Debug, Clone, PartialEq)]
enum MatchResult {
    None,
    Partial,
    Full,
}

fn marker_match_ci(remainder: &str, marker: &str) -> MatchResult {
    let remainder_lower = remainder.to_ascii_lowercase();
    let marker_lower = marker.to_ascii_lowercase();
    if remainder_lower.starts_with(&marker_lower) {
        MatchResult::Full
    } else if remainder.len() < marker.len()
        && marker_lower.starts_with(&remainder_lower)
    {
        MatchResult::Partial
    } else {
        MatchResult::None
    }
}

struct StartMatch {
    marker_index: Option<usize>,
    partial: bool,
}

struct EndMatch {
    length: Option<usize>,
    partial: bool,
}

struct CodeRun {
    length: usize,
    next_state: CodeState,
}

/// StreamingThinkTagParser — 流式思考标签解析器
///
/// 对应 Kotlin 的 StreamingThinkTagParser，包装 IncrementalThinkingParser。
pub struct StreamingThinkTagParser {
    markers: Vec<Marker>,
    marker_stack: Vec<usize>,
    code_state: CodeState,
    pending: String,
    line_prefix: bool,
    line_indent: usize,
    indented_code_line: bool,
}

impl StreamingThinkTagParser {
    pub fn new() -> Self {
        Self {
            markers: build_markers(),
            marker_stack: Vec::new(),
            code_state: CodeState::None,
            pending: String::new(),
            line_prefix: true,
            line_indent: 0,
            indented_code_line: false,
        }
    }

    /// 当前是否在思考块内
    pub fn in_thinking(&self) -> bool {
        !self.marker_stack.is_empty()
    }

    /// 当前待处理缓冲区内容
    pub fn pending_content(&self) -> &str {
        &self.pending
    }

    /// 处理一块流式内容
    pub fn feed(
        &mut self,
        content: &str,
        thinking_enabled: bool,
        on_text: &mut dyn FnMut(&str),
        on_thought: &mut dyn FnMut(&str),
    ) {
        if content.is_empty() {
            return;
        }
        self.pending.push_str(content);
        self.drain(false, thinking_enabled, on_text, on_thought);
    }

    /// 冲洗剩余缓冲区内容
    pub fn flush(
        &mut self,
        thinking_enabled: bool,
        on_text: &mut dyn FnMut(&str),
        on_thought: &mut dyn FnMut(&str),
    ) {
        self.drain(true, thinking_enabled, on_text, on_thought);
        self.pending.clear();
    }

    /// 重置状态
    pub fn reset(&mut self) {
        self.marker_stack.clear();
        self.code_state = CodeState::None;
        self.pending.clear();
        self.line_prefix = true;
        self.line_indent = 0;
        self.indented_code_line = false;
    }

    fn drain(
        &mut self,
        is_final: bool,
        thinking_enabled: bool,
        on_text: &mut dyn FnMut(&str),
        on_thought: &mut dyn FnMut(&str),
    ) {
        let mut text_buf = String::new();
        let mut thought_buf = String::new();
        let mut index = 0;

        // 为了在闭包中借用 self 的字段，我们先把需要的数据复制出来
        // 注意：drain 过程中 self.pending 不能被修改（除了最后截断）
        // 使用索引遍历
        let pending_len = self.pending.len();
        let pending_bytes = self.pending.as_bytes();

        while index < pending_len {
            let remainder = &self.pending[index..];

            if self.code_state == CodeState::None && !self.indented_code_line {
                let closing = self.marker_stack.last().copied();
                if let Some(closing_idx) = closing {
                    let closing_marker = &self.markers[closing_idx];
                    let close_match = self.end_match(remainder, &closing_marker.ends);
                    if close_match.partial && !is_final {
                        break;
                    }
                    if let Some(length) = close_match.length {
                        // emit thought
                        if !thought_buf.is_empty() {
                            if thinking_enabled {
                                on_thought(&thought_buf);
                            }
                            thought_buf.clear();
                        }
                        self.marker_stack.pop();
                        index += length;
                        self.reset_markdown_state_at_segment_boundary();
                        continue;
                    }
                    // 检查嵌套开始标记
                    let nested = self.find_start_match(remainder);
                    if nested.partial && !is_final {
                        break;
                    }
                    if let Some(marker_idx) = nested.marker_index {
                        self.marker_stack.push(marker_idx);
                        index += self.markers[marker_idx].start.len();
                        self.reset_markdown_state_at_segment_boundary();
                        continue;
                    }
                } else {
                    let start = self.find_start_match(remainder);
                    if start.partial && !is_final {
                        break;
                    }
                    if let Some(marker_idx) = start.marker_index {
                        // emit text
                        if !text_buf.is_empty() {
                            on_text(&text_buf);
                            text_buf.clear();
                        }
                        self.marker_stack.push(marker_idx);
                        index += self.markers[marker_idx].start.len();
                        self.reset_markdown_state_at_segment_boundary();
                        continue;
                    }
                }
            }

            let code_run = self.markdown_delimiter_run(index, is_final);
            if code_run.is_none() && !is_final && self.is_potential_markdown_delimiter(index) {
                break;
            }
            if let Some(run) = code_run {
                let literal = &self.pending[index..index + run.length];
                if self.in_thinking() {
                    thought_buf.push_str(literal);
                } else {
                    text_buf.push_str(literal);
                }
                for ch in literal.chars() {
                    self.update_line_state(ch);
                }
                self.code_state = run.next_state;
                index += run.length;
                continue;
            }

            // 追加单个字面字符
            if index < pending_len {
                // 安全地取一个 UTF-8 字符
                if let Some(ch) = self.pending[index..].chars().next() {
                    if self.in_thinking() {
                        thought_buf.push(ch);
                    } else {
                        text_buf.push(ch);
                    }
                    self.update_line_state(ch);
                    index += ch.len_utf8();
                } else {
                    break;
                }
            }
        }

        // 发射剩余缓冲区
        if !text_buf.is_empty() {
            on_text(&text_buf);
        }
        if !thought_buf.is_empty() {
            if thinking_enabled {
                on_thought(&thought_buf);
            }
        }

        // 保留未消费的部分
        self.pending = self.pending[index..].to_string();

        if is_final && !self.pending.is_empty() {
            if self.in_thinking() {
                if thinking_enabled {
                    on_thought(&self.pending.clone());
                }
            } else {
                on_text(&self.pending.clone());
            }
            for ch in self.pending.clone().chars() {
                self.update_line_state(ch);
            }
            self.pending.clear();
        }
    }

    fn find_start_match(&self, remainder: &str) -> StartMatch {
        for (i, marker) in self.markers.iter().enumerate() {
            match marker_match_ci(remainder, marker.start) {
                MatchResult::Full => return StartMatch { marker_index: Some(i), partial: false },
                MatchResult::Partial => return StartMatch { marker_index: None, partial: true },
                MatchResult::None => {}
            }
        }
        StartMatch { marker_index: None, partial: false }
    }

    fn end_match(&self, remainder: &str, endings: &[&str]) -> EndMatch {
        let mut partial = false;
        for ending in endings {
            match marker_match_ci(remainder, ending) {
                MatchResult::Full => return EndMatch { length: Some(ending.len()), partial: false },
                MatchResult::Partial => partial = true,
                MatchResult::None => {}
            }
        }
        EndMatch { length: None, partial }
    }

    fn markdown_delimiter_run(&self, index: usize, is_final: bool) -> Option<CodeRun> {
        let ch = self.pending.as_bytes()[index] as char;
        match &self.code_state {
            CodeState::None => {
                if self.indented_code_line {
                    return None;
                }
                if ch != '`' && ch != '~' {
                    return None;
                }
                let run = self.count_run(index, ch);
                if !is_final && index + run == self.pending.len() {
                    return None;
                }
                if self.line_prefix && self.line_indent <= 3 && run >= 3 {
                    Some(CodeRun { length: run, next_state: CodeState::Fence { character: ch, length: run } })
                } else if ch == '`' {
                    Some(CodeRun { length: run, next_state: CodeState::Inline { ticks: run } })
                } else {
                    None
                }
            }
            CodeState::Inline { ticks } => {
                if ch != '`' {
                    return None;
                }
                let run = self.count_run(index, ch);
                if !is_final && index + run == self.pending.len() {
                    return None;
                }
                if run == *ticks {
                    Some(CodeRun { length: run, next_state: CodeState::None })
                } else {
                    Some(CodeRun { length: run, next_state: CodeState::Inline { ticks: *ticks } })
                }
            }
            CodeState::Fence { character, length } => {
                if !self.line_prefix || self.line_indent > 3 || ch != *character {
                    return None;
                }
                let run = self.count_run(index, ch);
                if !is_final && index + run == self.pending.len() {
                    return None;
                }
                if run >= *length {
                    Some(CodeRun { length: run, next_state: CodeState::None })
                } else {
                    Some(CodeRun { length: run, next_state: CodeState::Fence { character: *character, length: *length } })
                }
            }
        }
    }

    fn is_potential_markdown_delimiter(&self, index: usize) -> bool {
        if self.indented_code_line {
            return false;
        }
        let ch = self.pending.as_bytes()[index] as char;
        match &self.code_state {
            CodeState::None => ch == '`' || (self.line_prefix && ch == '~'),
            CodeState::Inline { .. } => ch == '`',
            CodeState::Fence { character, .. } => {
                self.line_prefix && self.line_indent <= 3 && ch == *character
            }
        }
    }

    fn count_run(&self, index: usize, character: char) -> usize {
        let mut end = index;
        let chars: Vec<char> = self.pending[index..].chars().collect();
        for &c in &chars {
            if c == character {
                end += c.len_utf8();
            } else {
                break;
            }
        }
        end - index
    }

    fn update_line_state(&mut self, character: char) {
        if character == '\n' {
            self.line_prefix = true;
            self.line_indent = 0;
            self.indented_code_line = false;
        } else if self.line_prefix && character == ' ' && self.line_indent < 4 {
            self.line_indent += 1;
            if self.line_indent == 4 {
                self.indented_code_line = true;
            }
        } else if self.line_prefix && character == '\t' {
            self.indented_code_line = true;
            self.line_prefix = false;
        } else {
            self.line_prefix = false;
        }
    }

    fn reset_markdown_state_at_segment_boundary(&mut self) {
        self.code_state = CodeState::None;
        self.line_prefix = true;
        self.line_indent = 0;
        self.indented_code_line = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn collect(parser: &mut StreamingThinkTagParser, chunks: &[&str], thinking_enabled: bool) -> (Vec<String>, Vec<String>) {
        let mut texts = Vec::new();
        let mut thoughts = Vec::new();
        for chunk in chunks {
            parser.feed(chunk, thinking_enabled, &mut |t| texts.push(t.to_string()), &mut |t| thoughts.push(t.to_string()));
        }
        parser.flush(thinking_enabled, &mut |t| texts.push(t.to_string()), &mut |t| thoughts.push(t.to_string()));
        (texts, thoughts)
    }

    #[test]
    fn test_no_thinking_tags() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, thoughts) = collect(&mut parser, &["Hello, world!"], true);
        assert_eq!(texts, vec!["Hello, world!"]);
        assert!(thoughts.is_empty());
    }

    #[test]
    fn test_simple_thinking_block() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, thoughts) = collect(&mut parser, &["Before <think>reasoning</think> After"], true);
        assert_eq!(texts, vec!["Before ", " After"]);
        assert_eq!(thoughts, vec!["reasoning"]);
    }

    #[test]
    fn test_thinking_disabled() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, thoughts) = collect(&mut parser, &["Before <think>reasoning</think> After"], false);
        assert_eq!(texts, vec!["Before ", " After"]);
        assert!(thoughts.is_empty());
    }

    #[test]
    fn test_think_tag_split_across_chunks() {
        let mut parser = StreamingThinkTagParser::new();
        let mut texts = Vec::new();
        let mut thoughts = Vec::new();
        let t = &mut |s: &str| texts.push(s.to_string());
        let h = &mut |s: &str| thoughts.push(s.to_string());

        parser.feed("Hello <t", true, t, h);
        parser.feed("ink>my thought</th", true, t, h);
        parser.feed("ink> Done", true, t, h);
        parser.flush(true, t, h);

        assert_eq!(texts, vec!["Hello ", " Done"]);
        assert_eq!(thoughts, vec!["my thought"]);
    }

    #[test]
    fn test_thinking_tag_style() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, thoughts) = collect(&mut parser, &["A <thinking>inner</thinking> B"], true);
        assert_eq!(texts, vec!["A ", " B"]);
        assert_eq!(thoughts, vec!["inner"]);
    }

    #[test]
    fn test_multiple_thinking_blocks() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, thoughts) = collect(&mut parser, &["A <think>t1</think> B <think>t2</think> C"], true);
        assert_eq!(texts, vec!["A ", " B ", " C"]);
        assert_eq!(thoughts, vec!["t1", "t2"]);
    }

    #[test]
    fn test_nested_think_tags() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, thoughts) = collect(&mut parser, &["A <think>outer <think>inner</think> tail</think> B"], true);
        // 内层的</think>关闭内层，之后的tail还在外层，最后的</think>关闭外层
        assert_eq!(texts, vec!["A ", " B"]);
        // thoughts 应该包含 inner 和 tail (inner先被emit, 然后外层继续)
        assert_eq!(thoughts, vec!["inner", "tail"]);
    }

    #[test]
    fn test_empty_content() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, thoughts) = collect(&mut parser, &[""], true);
        assert!(texts.is_empty());
        assert!(thoughts.is_empty());
    }

    #[test]
    fn test_think_tag_at_start() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, thoughts) = collect(&mut parser, &["<think>hello world</think>text"], true);
        assert_eq!(texts, vec!["text"]);
        assert_eq!(thoughts, vec!["hello world"]);
    }

    #[test]
    fn test_incomplete_tag_at_end() {
        let mut parser = StreamingThinkTagParser::new();
        let mut texts = Vec::new();
        let mut thoughts = Vec::new();
        let t = &mut |s: &str| texts.push(s.to_string());
        let h = &mut |s: &str| thoughts.push(s.to_string());

        parser.feed("Hello <thi", true, t, h);
        // incomplete — nothing emitted yet, buffer held
        parser.flush(true, t, h);
        // on flush the partial tag is emitted as text
        assert_eq!(texts, vec!["Hello <thi"]);
        assert!(thoughts.is_empty());
    }

    #[test]
    fn test_in_thinking_state() {
        let mut parser = StreamingThinkTagParser::new();
        assert!(!parser.in_thinking());
        parser.feed("before ", true, &mut |_|{}, &mut |_|{});
        assert!(!parser.in_thinking());
        parser.feed("<think>", true, &mut |_|{}, &mut |_|{});
        assert!(parser.in_thinking());
        parser.feed("thinking...", true, &mut |_|{}, &mut |_|{});
        assert!(parser.in_thinking());
        parser.feed("</think>", true, &mut |_|{}, &mut |_|{});
        assert!(!parser.in_thinking());
    }

    #[test]
    fn test_code_block_ignored() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, _thoughts) = collect(&mut parser, &["```\n<think>not real tag\n```\n"], true);
        // The think tag inside a code fence should be treated as literal text
        assert!(texts.iter().any(|t| t.contains("<think>")));
    }

    #[test]
    fn test_inline_code_ignored() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, _thoughts) = collect(&mut parser, &["Use `<think>` as tag"], true);
        assert!(texts.iter().any(|t| t.contains("<think>")));
    }

    #[test]
    fn test_single_char_chunks() {
        let mut parser = StreamingThinkTagParser::new();
        let input = "A</think> B";
        let mut texts = Vec::new();
        let mut thoughts = Vec::new();
        let t = &mut |s: &str| texts.push(s.to_string());
        let h = &mut |s: &str| thoughts.push(s.to_string());

        for ch in input.chars() {
            let s = ch.to_string();
            parser.feed(&s, true, t, h);
        }
        parser.flush(true, t, h);

        assert_eq!(texts, vec!["A ", " B"]);
        assert_eq!(thoughts, vec!["thought"]);
    }

    #[test]
    fn test_reset() {
        let mut parser = StreamingThinkTagParser::new();
        parser.feed("<think>partial", true, &mut |_|{}, &mut |_|{});
        assert!(parser.in_thinking());
        parser.reset();
        assert!(!parser.in_thinking());
        assert_eq!(parser.pending_content(), "");
    }

    #[test]
    fn test_channel_format() {
        let mut parser = StreamingThinkTagParser::new();
        let (texts, thoughts) = collect(&mut parser, &["A <|channel|>thought<|message|>inner<|end|> B"], true);
        assert_eq!(texts, vec!["A ", " B"]);
        assert_eq!(thoughts, vec!["inner"]);
    }
}
