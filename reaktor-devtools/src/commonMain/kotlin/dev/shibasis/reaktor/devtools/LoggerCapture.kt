package dev.shibasis.reaktor.devtools

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

class AgentLogWriter(private val sink: LogSink) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) =
        sink.log(severity.level, tag, message.redacted() + throwable?.let { " (${it::class.simpleName}: ${it.message.orEmpty().redacted()})" }.orEmpty(), mirrored = false)
}

private val bareSecrets = listOf(
    Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*"),
    Regex("rkr_[A-Za-z0-9_-]+"),
)

internal val sensitiveFieldName = Regex("(?i)authorization|cookie|token|secret|api[-_]?key|password|credential|email|phone|user[-_]?id|session|request[-_]?body|response[-_]?body|payload|content|message|text|transcript|prompt")

private val namedSecret = Regex("(?i)((?:access|refresh|id)?[-_]?token|secret|password|authorization|api[-_]?key)([\"']?\\s*[:=]\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|(?:Bearer\\s+|Basic\\s+)?[^\\s\"'&,}]+)")
private val urls = Regex("(?i)[a-z][a-z0-9+.-]*://[^\\s\"'<>]+")
private val urlUserInfo = Regex("(?i)^([a-z][a-z0-9+.-]*://)[^/?#]*@")

internal fun String.redactedUrl(): String = substringBefore('?').substringBefore('#').replace(urlUserInfo, "$1")

internal fun String.redacted(): String = lineSequence().firstOrNull().orEmpty().masked()

internal fun String.masked(): String {
    val named = namedSecret.replace(urls.replace(this) { it.value.redactedUrl() }) { match ->
        val quote = match.groupValues[3].first().takeIf { it == '\"' || it == '\'' }?.toString().orEmpty()
        match.groupValues[1] + match.groupValues[2] + quote + "***" + quote
    }
    return bareSecrets.fold(named) { text, secret -> text.replace(secret, "***") }
}

private val Severity.level: LogLevel
    get() = when (this) {
        Severity.Verbose -> LogLevel.Verbose
        Severity.Debug -> LogLevel.Debug
        Severity.Info -> LogLevel.Info
        Severity.Warn -> LogLevel.Warn
        Severity.Error -> LogLevel.Error
        Severity.Assert -> LogLevel.Assert
    }

fun DevToolsAgent.captureLogger() = Logger.addLogWriter(AgentLogWriter(log))
