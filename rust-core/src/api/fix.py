line_content = '''    Some(GeminiContent {
        role: "user".to_string(),
        parts: vec![GeminiPart {
            text: None,
            inline_None,
            function_call: None,
            function_response: Some(fr),
        }],
    })'''

fixed = '''    Some(GeminiContent {
        role: "user".to_string(),
        parts: vec![GeminiPart {
            text: None,
            inline_None,
            function_call: None,
            function_response: Some(fr),
            thought_signature: None,
        }],
    })'''

with open('gemini.rs.fixed', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(line_content, fixed)

with open('gemini.rs', 'w', encoding='utf-8') as f:
    f.write(content)

print("Fixed successfully!")
