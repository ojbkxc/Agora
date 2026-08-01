package com.newoether.agora.api

/**
 * JNI bridge to the `agora_rs` native library.
 *
 * All native calls are blocking and MUST be called from a background thread
 * (e.g. `withContext(Dispatchers.IO)`). The library is loaded once on first
 * access of this object.
 */
object RustProvider {
    init {
        System.loadLibrary("agora_rs")
    }

    /**
     * Create a Rust-side provider instance.
     *
     * @param providerType one of "openai", "anthropic", "gemini", "ollama"
     * @param configJson   JSON-serialised [RustProviderConfig]
     * @return opaque handle (positive) or a negative error code
     */
    external fun nativeCreateProvider(providerType: String, configJson: String): Long

    /**
     * Start a streaming generation.
     *
     * The callback is invoked on a Rust-managed thread for every SSE event
     * until the stream completes or errors. The return value is a final JSON
     * summary string on success, or a JSON error on failure.
     *
     * @param handle       from [nativeCreateProvider]
     * @param messagesJson JSON array of messages
     * @param configJson   JSON-serialised per-request config overrides
     * @param callback     streaming event receiver
     * @return final JSON summary / error string
     */
    external fun nativeGenerate(
        handle: Long,
        messagesJson: String,
        configJson: String,
        callback: RustStreamCallback
    ): String

    /**
     * Fetch available model IDs for the provider.
     *
     * @param handle  from [nativeCreateProvider]
     * @param apiKey  provider API key
     * @param baseUrl provider base URL (may be empty for defaults)
     * @return JSON array of model-id strings, or JSON error
     */
    external fun nativeFetchModels(handle: Long, apiKey: String, baseUrl: String): String

    /**
     * Release the Rust-side provider and free associated resources.
     * Safe to call multiple times; no-op for invalid handles.
     */
    external fun nativeDestroyProvider(handle: Long)

    /**
     * Callback interface for streaming generation events.
     *
     * Each [onEvent] invocation carries a JSON-serialised [StreamEvent].
     * Implementations must be thread-safe as callbacks arrive from the Rust
     * runtime thread.
     */
    interface RustStreamCallback {
        fun onEvent(eventJson: String)
    }
}
