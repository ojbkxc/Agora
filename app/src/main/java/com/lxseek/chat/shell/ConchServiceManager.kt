package com.lxseek.chat.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.lang.reflect.Method
import java.security.SecureRandom

/**
 * Manages the embedded Conch shell server (Go library compiled via gomobile).
 *
 * The Conch .aar is built by server/conch/build-android.sh and placed in app/libs/.
 * To keep the app compilable when the .aar is absent (e.g. fresh checkout without
 * the Go toolchain), all calls to the gomobile-generated `mobile.Mobile` class are
 * done via reflection. When the .aar is present, [isAvailable] returns true and
 * [start]/[stop]/[publicKey] delegate to the native Go server.
 */
object ConchServiceManager {
    private const val TAG = "ConchServiceManager"
    private const val DEFAULT_PORT = 14216
    private const val ANDROID_SHELL = "/system/bin/sh"
    private const val PREFS_NAME = "conch_embedded"
    private const val PREF_API_KEY = "api_key"

    private var mobileClass: Class<*>? = null
    private var startMethod: Method? = null
    private var stopMethod: Method? = null
    private var publicKeyMethod: Method? = null
    private var isRunningMethod: Method? = null
    private var setShellPathMethod: Method? = null
    private var versionMethod: Method? = null
    private var initialized = false

    val isAvailable: Boolean
        get() {
            ensureInitialized()
            return mobileClass != null
        }

    private fun ensureInitialized() {
        if (initialized) return
        initialized = true
        try {
            val cls = Class.forName("mobile.Mobile")
            mobileClass = cls
            startMethod = cls.getMethod("start", String::class.java, Long::class.javaPrimitiveType, String::class.java)
            stopMethod = cls.getMethod("stop")
            publicKeyMethod = cls.getMethod("publicKey")
            isRunningMethod = cls.getMethod("isRunning")
            setShellPathMethod = cls.getMethod("setShellPath", String::class.java)
            versionMethod = cls.getMethod("version")
            Log.i(TAG, "Embedded Conch available: ${versionMethod?.invoke(null)}")
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "Embedded Conch not available (conch.aar not built)")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Embedded Conch API mismatch", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize embedded Conch", e)
        }
    }

    fun start(context: Context, apiKey: String, port: Int = DEFAULT_PORT): Boolean {
        ensureInitialized()
        val cls = mobileClass ?: run {
            Log.w(TAG, "Cannot start: embedded Conch not available")
            return false
        }
        return try {
            setShellPathMethod?.invoke(null, ANDROID_SHELL)
            val jobDir = File(context.filesDir, "conch/jobs").absolutePath
            File(jobDir).mkdirs()
            startMethod?.invoke(null, apiKey, port.toLong(), jobDir)
            Log.i(TAG, "Embedded Conch started on 127.0.0.1:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start embedded Conch", e)
            false
        }
    }

    fun stop(): Boolean {
        ensureInitialized()
        val cls = mobileClass ?: return false
        return try {
            stopMethod?.invoke(null)
            Log.i(TAG, "Embedded Conch stopped")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop embedded Conch", e)
            false
        }
    }

    fun publicKey(): String? {
        ensureInitialized()
        val cls = mobileClass ?: return null
        return try {
            publicKeyMethod?.invoke(null) as? String
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get public key", e)
            null
        }
    }

    fun isRunning(): Boolean {
        ensureInitialized()
        val cls = mobileClass ?: return false
        return try {
            isRunningMethod?.invoke(null) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun version(): String? {
        ensureInitialized()
        val cls = mobileClass ?: return null
        return try {
            versionMethod?.invoke(null) as? String
        } catch (e: Exception) {
            null
        }
    }

    fun getOrGenerateApiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_API_KEY, null)
        if (existing != null) return existing
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val key = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        prefs.edit().putString(PREF_API_KEY, key).apply()
        return key
    }

    fun serverUrl(port: Int = DEFAULT_PORT): String = "http://127.0.0.1:$port"
}
