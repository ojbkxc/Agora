package com.newoether.agora.api.openai

class CustomOpenAiProvider(
    override val name: String,
    override val defaultBaseUrl: String
) : BaseOpenAiProvider() {

    // 401 是确定性错误（API Key 不会在重试间改变），不应重试
    override val retryableStatusCodes: Set<Int> = setOf(429, 502, 503, 504)

    override val retryMissingV1BaseUrl: Boolean = true

    // Reasoning arrives either as reasoning_content deltas (vLLM, DeepSeek-compatible servers)
    // or inline <think> tags in content (llama.cpp server, LM Studio) — parse both.
    override val parseInlineThinkTags: Boolean = true
}
