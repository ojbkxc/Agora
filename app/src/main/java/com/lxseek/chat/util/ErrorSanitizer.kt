package com.lxseek.chat.util

object ErrorSanitizer {
    private val ipPortPattern = Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?\\b")
    private val fromPattern = Regex("(?i)\\bfrom\\s+/[\\d.]+")
    private val toPattern = Regex("(?i)\\bto\\s+host/[\\w.]+")

    fun stripHostAndIp(text: String): String =
        text
            .replace(ipPortPattern, "[redacted]")
            .replace(fromPattern, "from [redacted]")
            .replace(toPattern, "to [redacted]")

    fun sanitize(throwable: Throwable): String {
        val message = throwable.message ?: throwable::class.java.simpleName
        return stripHostAndIp(message)
    }
}
