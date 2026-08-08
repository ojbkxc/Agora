package com.newoether.agora.api

/**
 * Rust-backed [LlmProvider] for custom OpenAI-compatible endpoints.
 *
 * Unlike [RustOpenAiProvider] which uses a fixed name and default base URL,
 * this class accepts a user-defined name and base URL for custom API endpoints.
 */
class RustCustomOpenAiProvider(
    override val name: String,
    override val defaultBaseUrl: String
) : RustOpenAiProvider(defaultBaseUrl = defaultBaseUrl)

/**
 * Rust-backed [LlmProvider] for custom Anthropic-compatible endpoints.
 */
class RustCustomAnthropicProvider(
    override val name: String,
    override val defaultBaseUrl: String
) : RustAnthropicProvider()