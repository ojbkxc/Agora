package com.newoether.agora.util

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Anonymous, opt-in crash reporting.
 *
 * On an uncaught exception we persist a single pending report to disk and then let the
 * platform's default handler terminate the process normally. On the next launch the UI
 * detects the pending report and offers the user a one-tap, opt-in way to file a
 * pre-filled GitHub issue on the project's repository; nothing is ever sent without
 * that explicit action.
 *
 * The report carries only a stack trace plus coarse, non-identifying environment data
 * (app version, Android API level, device model) — no user content, no device IDs.
 */
object CrashReporter {

    private const val ISSUE_URL = "https://github.com/ojbkxc/Agora/issues/new"
    private const val DIR = "crash"
    private const val FILE = "pending.json"
    private const val MAX_TRACE_CHARS = 60_000

    /** Coarse, non-identifying app identity captured once at install time. */
    private data class AppInfo(val versionName: String, val versionCode: Long)

    @Volatile private var appInfo: AppInfo = AppInfo("?", 0)

    /** Rolling diagnostic trail attached to crash reports. Helps pin down crashes we can't
     *  reproduce locally (e.g. the foreground-service start-in-time timeout) by recording
     *  what happened just before, with timestamps. No user content — only coarse event tags. */
    private const val MAX_BREADCRUMBS = 60
    private val breadcrumbs = ConcurrentLinkedDeque<String>()

    /** Append a timestamped breadcrumb to the diagnostic trail (thread-safe, bounded). */
    fun note(message: String) {
        breadcrumbs.addLast("${System.currentTimeMillis()} $message")
        while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.pollFirst()
    }

    /**
     * Registers the global uncaught-exception handler. Call once, as early as possible
     * (Application.onCreate), so crashes during startup are captured too.
     */
    fun install(context: Context) {
        appInfo = readAppInfo(context)
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(appContext, throwable) }
            // Always chain to the platform handler so the process dies as it normally would.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the pending crash report JSON, or null if none is waiting. */
    fun pendingReport(context: Context): String? {
        val f = reportFile(context)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /** Discards the pending report (after the user submits or dismisses it). */
    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    /**
     * Builds a pre-filled GitHub issue URL for the given crash report so the user can
     * review and submit it manually in their browser. No network access required.
     */
    fun issueUrl(reportJson: String): String {
        val obj = runCatching { JSONObject(reportJson) }.getOrNull() ?: JSONObject()
        val trace = obj.optString("trace", "")
        val version = obj.optString("appVersion", "?")
        val code = obj.optLong("versionCode", 0)
        val api = obj.optInt("androidApi", 0)
        val release = obj.optString("androidRelease", "?")
        val device = obj.optString("device", "?")
        val ts = obj.optLong("ts", 0)
        val crumbs = runCatching {
            obj.optJSONArray("breadcrumbs")?.let { arr ->
                buildString {
                    for (i in 0 until arr.length()) {
                        if (isNotEmpty()) append('\n')
                        append("- ").append(arr.optString(i))
                    }
                }
            }
        }.getOrNull().orEmpty()

        val title = "Crash report — v$version"
        val body = buildString {
            append("## Crash report\n\n")
            append("| Field | Value |\n|---|---|\n")
            append("| App version | $version ($code) |\n")
            append("| Android API | $api |\n")
            append("| Android release | $release |\n")
            append("| Device | $device |\n")
            append("| Timestamp | $ts |\n\n")
            if (crumbs.isNotEmpty()) {
                append("### Breadcrumbs\n\n")
                append(crumbs).append("\n\n")
            }
            append("### Stack trace\n\n")
            append("```\n")
            append(trace)
            append("\n```\n")
        }

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedBody = URLEncoder.encode(body, "UTF-8")
        return "$ISSUE_URL?title=$encodedTitle&body=$encodedBody"
    }

    private fun writeReport(context: Context, throwable: Throwable) {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
            .toString().take(MAX_TRACE_CHARS)
        val json = JSONObject().apply {
            put("trace", trace)
            put("appVersion", appInfo.versionName)
            put("versionCode", appInfo.versionCode)
            put("androidApi", Build.VERSION.SDK_INT)
            put("androidRelease", Build.VERSION.RELEASE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("ts", System.currentTimeMillis())
            put("breadcrumbs", JSONArray(breadcrumbs.toList()))
        }
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        File(dir, FILE).writeText(json.toString())
    }

    private fun reportFile(context: Context): File = File(File(context.filesDir, DIR), FILE)

    @Suppress("DEPRECATION")
    private fun readAppInfo(context: Context): AppInfo = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else pi.versionCode.toLong()
        AppInfo(pi.versionName ?: "?", code)
    }.getOrDefault(AppInfo("?", 0))
}
