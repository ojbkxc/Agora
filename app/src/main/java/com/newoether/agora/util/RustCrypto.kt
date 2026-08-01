package com.newoether.agora.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JNI bridge to the `agora_rs` native cryptographic functions.
 *
 * Replaces the pure-Kotlin [ShellCrypto] for X25519 key exchange,
 * AES-256-GCM encryption, HMAC-SHA256 signing, and SHA-256 hashing.
 * All native calls are blocking and run on [Dispatchers.IO].
 */
object RustCrypto {
    init {
        System.loadLibrary("agora_rs")
    }

    /**
     * Generate an ephemeral X25519 key pair.
     *
     * @return JSON string: `{"public_key": "<base64url>", "handle": <long>}`
     *         where `handle` is needed for [nativeDeriveAesKey].
     */
    external fun nativeGenerateKeyPair(): String

    /**
     * Derive a shared AES-256 key from our ephemeral private key (identified
     * by [handle]) and the server's X25519 public key.
     *
     * @param handle         from [nativeGenerateKeyPair]
     * @param serverPublicKey base64url-encoded server public key
     * @return JSON string: `{"key": "<base64url-aes-key>"}` or `{"error": "..."}`
     */
    external fun nativeDeriveAesKey(handle: Long, serverPublicKey: String): String

    /**
     * Encrypt [plaintext] with AES-256-GCM using [key].
     *
     * @param key       base64url-encoded AES key
     * @param plaintext UTF-8 text to encrypt
     * @return base64url(nonce ‖ ciphertext ‖ tag)
     */
    external fun nativeEncrypt(key: String, plaintext: String): String

    /**
     * Decrypt [ciphertext] with AES-256-GCM using [key].
     *
     * @param key        base64url-encoded AES key
     * @param ciphertext base64url(nonce ‖ ciphertext ‖ tag)
     * @return UTF-8 plaintext
     * @throws RuntimeException on authentication failure
     */
    external fun nativeDecrypt(key: String, ciphertext: String): String

    /**
     * HMAC-SHA256 signing matching the Conch protocol.
     *
     * @param apiKey        API key used as HMAC secret
     * @param timestamp     Unix seconds
     * @param method        HTTP method (e.g. "POST")
     * @param path          request path (e.g. "/execute")
     * @param bodySha256    hex-encoded SHA-256 of the request body
     * @param nonce         base64url-encoded random nonce
     * @param clientPubKey  base64url-encoded client public key
     * @return hex-encoded HMAC-SHA256 signature
     */
    external fun nativeSign(
        apiKey: String,
        timestamp: Long,
        method: String,
        path: String,
        bodySha256: String,
        nonce: String,
        clientPubKey: String
    ): String

    /**
     * SHA-256 hash of [data], returned as lowercase hex.
     */
    external fun nativeSha256Hex(data: String): String

    /**
     * Generate a cryptographically random nonce (base64url-encoded, 16 bytes).
     */
    external fun nativeGenerateNonce(): String

    // ── Kotlin convenience wrappers ──────────────────────────────────

    @Serializable
    private data class KeyPairResult(
        @kotlinx.serialization.SerialName("public_key") val publicKey: String = "",
        val handle: Long = -1,
        val error: String? = null,
    )

    @Serializable
    private data class AesKeyResult(
        val key: String = "",
        val error: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Generate an ephemeral key pair and return the (publicKey, handle) pair.
     * Throws on native failure.
     */
    suspend fun generateKeyPair(): Pair<String, Long> = withContext(Dispatchers.IO) {
        val result = json.decodeFromString<KeyPairResult>(nativeGenerateKeyPair())
        if (result.error != null) throw IllegalStateException("Key pair generation failed: ${result.error}")
        if (result.handle < 0) throw IllegalStateException("Key pair generation returned invalid handle")
        Pair(result.publicKey, result.handle)
    }

    /**
     * Derive an AES key from a previously generated ephemeral key pair and the
     * server's public key. Returns the base64url-encoded AES key.
     */
    suspend fun deriveAesKey(handle: Long, serverPublicKey: String): String =
        withContext(Dispatchers.IO) {
            val result = json.decodeFromString<AesKeyResult>(nativeDeriveAesKey(handle, serverPublicKey))
            if (result.error != null) throw IllegalStateException("AES key derivation failed: ${result.error}")
            result.key
        }

    /**
     * Encrypt plaintext and return the base64url-encoded ciphertext.
     */
    suspend fun encrypt(key: String, plaintext: String): String = withContext(Dispatchers.IO) {
        nativeEncrypt(key, plaintext)
    }

    /**
     * Decrypt a base64url-encoded ciphertext and return the plaintext.
     */
    suspend fun decrypt(key: String, ciphertext: String): String = withContext(Dispatchers.IO) {
        nativeDecrypt(key, ciphertext)
    }

    /**
     * Produce an HMAC-SHA256 signature matching the Conch wire protocol.
     */
    suspend fun sign(
        apiKey: String,
        timestamp: Long,
        method: String,
        path: String,
        bodySha256: String,
        nonce: String,
        clientPubKey: String
    ): String = withContext(Dispatchers.IO) {
        nativeSign(apiKey, timestamp, method, path, bodySha256, nonce, clientPubKey)
    }

    /**
     * SHA-256 hash of a UTF-8 string, returned as lowercase hex.
     */
    suspend fun sha256Hex(data: String): String = withContext(Dispatchers.IO) {
        nativeSha256Hex(data)
    }

    /**
     * Generate a random nonce (base64url-encoded).
     */
    suspend fun generateNonce(): String = withContext(Dispatchers.IO) {
        nativeGenerateNonce()
    }
}
