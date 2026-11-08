package dev.shibasis.reaktor.cloud.http

import java.net.http.HttpResponse

object Retry {
    const val Attempts = 3
    const val LongestWaitMillis = 10_000L
    val Repeatable = setOf("GET", "HEAD", "PUT", "DELETE", "OPTIONS")

    fun waitMillis(response: HttpResponse<*>, attempt: Int, repeatable: Boolean = response.request().method() in Repeatable): Long? {
        val status = response.statusCode()
        if (attempt >= Attempts || !(status == 429 || repeatable && status in 500..599)) return null
        val asked = response.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.times(1000)
        return (asked ?: (400L shl (attempt - 1))).coerceIn(0, LongestWaitMillis)
    }
}
