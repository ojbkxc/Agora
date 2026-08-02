package com.newoether.agora.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JNI bridge to the `agora_rs` native shell client.
 *
 * Replaces the pure-Kotlin [ShellClient] for encrypted command execution and
 * file operations against a Conch server. All native calls are blocking and
 * MUST be invoked from [Dispatchers.IO].
 */
object RustShell {
    init {
        System.loadLibrary("agora_rs")
    }

    /**
     * Create a Rust-side shell client.
     *
     * @param serverUrl  Conch server base URL
     * @param apiKey     authentication API key
     * @param cachedKey  previously cached server public key (may be empty)
     * @return opaque handle (positive) or negative error code
     */
    external fun nativeCreateShellClient(
        serverUrl: String,
        apiKey: String,
        cachedKey: String
    ): Long

    /**
     * Fetch and verify the server's public key.
     *
     * @param handle from [nativeCreateShellClient]
     * @return true if the key was successfully obtained and verified
     */
    external fun nativeFetchPublicKey(handle: Long): Boolean

    /**
     * Execute a command on the remote server.
     *
     * @param handle    from [nativeCreateShellClient]
     * @param command   shell command to execute
     * @param timeoutMs execution timeout in milliseconds
     * @param workdir   working directory (may be empty for default)
     * @return JSON string: `{"output": "...", "exit_code": 0}` or `{"error": "..."}`
     */
    external fun nativeExecuteCommand(
        handle: Long,
        command: String,
        timeoutMs: Int,
        workdir: String
    ): String

    /**
     * Read file contents from the remote server.
     *
     * @param handle from [nativeCreateShellClient]
     * @param path   absolute file path on the server
     * @param offset byte offset to start reading
     * @param limit  max bytes to read (0 for default 1 MB)
     * @return JSON: `{"content": "...", "lines": N, "totalLines": M}` or `{"error": "..."}`
     */
    external fun nativeFileRead(
        handle: Long,
        path: String,
        offset: Long,
        limit: Long
    ): String

    /**
     * Write content to a file on the remote server.
     *
     * @param handle  from [nativeCreateShellClient]
     * @param path    absolute file path on the server
     * @param content UTF-8 content to write
     * @return JSON: `{}` on success or `{"error": "..."}`
     */
    external fun nativeFileWrite(
        handle: Long,
        path: String,
        content: String
    ): String

    /**
     * Glob for files matching [pattern] under [basePath].
     *
     * @param handle   from [nativeCreateShellClient]
     * @param pattern  glob pattern (e.g. "*.kt")
     * @param basePath root directory to search (may be empty)
     * @return JSON: `{"files": [...]}` or `{"error": "..."}`
     */
    external fun nativeFileGlob(
        handle: Long,
        pattern: String,
        basePath: String
    ): String

    /**
     * Search file contents for [pattern] under [basePath].
     *
     * @param handle   from [nativeCreateShellClient]
     * @param pattern  regex pattern
     * @param basePath root directory to search (may be empty)
     * @return JSON: `{"matches": [{"path": "...", "line": N, "content": "..."}]}` or `{"error": "..."}`
     */
    external fun nativeFileGrep(
        handle: Long,
        pattern: String,
        basePath: String
    ): String

    /**
     * Destroy the shell client and free associated resources.
     * Safe to call multiple times; no-op for invalid handles.
     */
    external fun nativeDestroyShellClient(handle: Long)

    // ── Kotlin convenience wrappers ──────────────────────────────────

    @Serializable
    data class CommandResult(
        val output: String = "",
        @kotlinx.serialization.SerialName("exit_code") val exitCode: Int = -1,
        val error: String? = null,
    )

    @Serializable
    data class FileReadResult(
        val content: String = "",
        val lines: Int = 0,
        @kotlinx.serialization.SerialName("totalLines") val totalLines: Int = 0,
        val error: String? = null,
    )

    @Serializable
    data class FileWriteResult(
        val error: String? = null,
    )

    @Serializable
    data class FileGlobResult(
        val files: List<String> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    data class GrepMatch(
        val path: String = "",
        val line: Int = 0,
        val content: String = "",
    )

    @Serializable
    data class FileGrepResult(
        val matches: List<GrepMatch> = emptyList(),
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Create a shell client and return an opaque handle.
     * Throws on native failure.
     */
    suspend fun createClient(
        serverUrl: String,
        apiKey: String,
        cachedKey: String = ""
    ): Long = withContext(Dispatchers.IO) {
        val handle = nativeCreateShellClient(serverUrl, apiKey, cachedKey)
        if (handle < 0) throw IllegalStateException("Shell client creation failed (code $handle)")
        handle
    }

    /**
     * Fetch the server's public key. Must be called before any encrypted operation.
     *
     * @return true if key was obtained and verified
     */
    suspend fun fetchPublicKey(handle: Long): Boolean = withContext(Dispatchers.IO) {
        nativeFetchPublicKey(handle)
    }

    /**
     * Execute a command on the remote server and return the parsed result.
     */
    suspend fun executeCommand(
        handle: Long,
        command: String,
        timeoutMs: Int,
        workdir: String = ""
    ): CommandResult = withContext(Dispatchers.IO) {
        val raw = nativeExecuteCommand(handle, command, timeoutMs, workdir)
        try {
            json.decodeFromString<CommandResult>(raw)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse command result: $raw", e)
            CommandResult(error = raw)
        }
    }

    /**
     * Read a file from the remote server and return the parsed result.
     */
    suspend fun fileRead(
        handle: Long,
        path: String,
        offset: Long = 0,
        limit: Long = 0
    ): FileReadResult = withContext(Dispatchers.IO) {
        val raw = nativeFileRead(handle, path, offset, limit)
        try {
            json.decodeFromString<FileReadResult>(raw)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse file read result: $raw", e)
            FileReadResult(error = raw)
        }
    }

    /**
     * Write content to a file on the remote server.
     *
     * @return error message or null on success
     */
    suspend fun fileWrite(
        handle: Long,
        path: String,
        content: String
    ): String? = withContext(Dispatchers.IO) {
        val raw = nativeFileWrite(handle, path, content)
        try {
            json.decodeFromString<FileWriteResult>(raw).error
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse file write result: $raw", e)
            raw
        }
    }

    /**
     * Glob for files matching [pattern] on the remote server.
     */
    suspend fun fileGlob(
        handle: Long,
        pattern: String,
        basePath: String = ""
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val raw = nativeFileGlob(handle, pattern, basePath)
        try {
            val result = json.decodeFromString<FileGlobResult>(raw)
            if (result.error != null) Result.failure(Exception(result.error))
            else Result.success(result.files)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse glob result: $raw", e)
            Result.failure(e)
        }
    }

    /**
     * Search file contents on the remote server.
     */
    suspend fun fileGrep(
        handle: Long,
        pattern: String,
        basePath: String = ""
    ): Result<List<GrepMatch>> = withContext(Dispatchers.IO) {
        val raw = nativeFileGrep(handle, pattern, basePath)
        try {
            val result = json.decodeFromString<FileGrepResult>(raw)
            if (result.error != null) Result.failure(Exception(result.error))
            else Result.success(result.matches)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Failed to parse grep result: $raw", e)
            Result.failure(e)
        }
    }

    /**
     * Destroy the shell client. Safe to call multiple times.
     */
    suspend fun destroyClient(handle: Long) = withContext(Dispatchers.IO) {
        nativeDestroyShellClient(handle)
    }

    private const val TAG = "RustShell"
}
