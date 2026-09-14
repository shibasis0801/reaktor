package dev.shibasis.reaktor.devtools

/**
 * The app's own log, structured.
 *
 * Scraping `logcat` or `os_log` recovers a string and loses the fields; recording here keeps
 * level, subsystem and key/value pairs intact, and keeps the entry correlated with the call it
 * happened inside. Entries are also handed to [mirror] so they still appear in the platform log
 * where a developer expects to find them.
 */
class LogSink(
    private val stream: FactStream,
    private var minimumLevel: LogLevel = LogLevel.Debug,
    private val mirror: (LogLevel, String, String) -> Unit = { _, _, _ -> },
) {
    fun setMinimumLevel(level: LogLevel) {
        minimumLevel = level
    }

    fun log(
        level: LogLevel,
        subsystem: String,
        message: String,
        fields: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
        correlationId: String? = null,
    ) {
        if (level.ordinal < minimumLevel.ordinal) return
        stream.emit { sequence, nanos ->
            AgentFact.Log(
                sequence = sequence,
                monotonicNanos = nanos,
                level = level,
                subsystem = subsystem,
                message = message,
                fields = fields,
                correlationId = correlationId,
                throwable = throwable?.stackTraceToString(),
            )
        }
        mirror(level, subsystem, message)
    }

    fun verbose(subsystem: String, message: String) = log(LogLevel.Verbose, subsystem, message)
    fun debug(subsystem: String, message: String) = log(LogLevel.Debug, subsystem, message)
    fun info(subsystem: String, message: String) = log(LogLevel.Info, subsystem, message)
    fun warn(subsystem: String, message: String) = log(LogLevel.Warn, subsystem, message)
    fun error(subsystem: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.Error, subsystem, message, throwable = throwable)
}
