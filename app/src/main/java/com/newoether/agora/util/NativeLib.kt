package com.newoether.agora.util

/**
 * Centralized loader for the `agora_rs` native library.
 *
 * All Rust JNI bridges ([RustShell], [RustCrypto], [com.newoether.agora.api.RustProvider],
 * [com.newoether.agora.api.RustEmbeddingClient]) route their `System.loadLibrary` through
 * this object so that a missing/incompatible `.so` (e.g. running on an x86 emulator while
 * `abiFilters` is restricted to `arm64-v8a`, or a failed Rust build) does not crash the
 * process with an uncaught `UnsatisfiedLinkError` during `object` initialization.
 *
 * Callers invoke [ensureLoaded] before any `external fun` invocation; it throws a
 * catchable [IllegalStateException] (an `Exception`, not an `Error`) so existing
 * `catch (e: Exception)` handlers in the JNI wrappers convert the failure into a
 * business-level error instead of killing the process.
 */
object NativeLib {
    private const val TAG = "NativeLib"
    private const val LIB_NAME = "agora_rs"

    @Volatile
    private var loadAttempted = false

    @Volatile
    private var loadError: Throwable? = null

    /** True iff [System.loadLibrary] completed without throwing. */
    val loaded: Boolean get() = loadAttempted && loadError == null

    /**
     * Idempotently attempt to load the native library. Safe to call from any
     * `object` init block; never throws.
     */
    fun load() {
        if (loadAttempted) return
        loadAttempted = true
        try {
            System.loadLibrary(LIB_NAME)
            DebugLog.d(TAG, "$LIB_NAME loaded")
        } catch (e: UnsatisfiedLinkError) {
            loadError = e
            DebugLog.e(TAG, "Failed to load $LIB_NAME — native features disabled", e)
        } catch (e: SecurityException) {
            loadError = e
            DebugLog.e(TAG, "Security manager denied loading $LIB_NAME", e)
        }
    }

    /**
     * Ensure the native library is loaded, attempting to load it if necessary.
     * Intended as the first statement of every JNI wrapper method so that a
     * missing `.so` surfaces as a catchable exception rather than an
     * `UnsatisfiedLinkError` escaping the wrapper.
     */
    fun ensureLoaded() {
        load()
        if (!loaded) {
            throw IllegalStateException(
                "$LIB_NAME native library is not loaded" +
                    (loadError?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: "")
            )
        }
    }
}