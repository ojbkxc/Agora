package com.newoether.agora.api

// RustOpenAiProvider and RustAnthropicProvider now accept name + defaultBaseUrl directly.
// These aliases are kept for backward compatibility with existing callers.

typealias RustCustomOpenAiProvider = RustOpenAiProvider
typealias RustCustomAnthropicProvider = RustAnthropicProvider