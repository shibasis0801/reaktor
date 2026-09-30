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

private val namedSecret = Regex("(?i)((?:access|refresh|id)?_?token|secret|password|authorization|api_?key)([\"']?\\s*[:=]\\s*[\"']?)((?:Bearer\\s+)?[^\\s\"'&,}]+)")

internal fun String.redacted(): String {
    val summary = lineSequence().firstOrNull().orEmpty()
    val named = namedSecret.replace(summary) { match -> match.groupValues[1] + match.groupValues[2] + "***" }
    return bareSecrets.fold(named) { line, secret -> line.replace(secret, "***") }
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
