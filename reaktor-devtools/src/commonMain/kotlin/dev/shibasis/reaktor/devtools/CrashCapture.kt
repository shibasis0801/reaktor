package dev.shibasis.reaktor.devtools

/**
 * Somewhere a crash report can survive the process that produced it.
 *
 * The agent cannot deliver a crash over a socket while it is dying, so the report is written
 * synchronously and replayed at the next handshake. This is the only reason the agent touches
 * disk at all, and it is why the first thing a developer sees after a crash is the crash.
 */
interface CrashStore {
    fun write(report: String)
    fun readAll(): List<String>
    fun clear()
}

expect fun crashStore(applicationId: String): CrashStore

/**
 * Installs the process-wide crash hook.
 *
 * Chains to whatever handler was already installed rather than replacing it: Crashlytics and the
 * platform's own reporter are still entitled to the crash, and a developer tool that swallows it
 * would be worse than no tool.
 */
expect fun installCrashHandler(agent: DevToolsAgent, store: CrashStore): Cancellable?

/**
 * The buffered facts that lead up to a crash, rendered for persistence.
 *
 * Deliberately strings rather than typed facts: this runs on a dying process, where the less work
 * done the better, and the sequence numbers are enough for the workbench to line them back up.
 */
internal fun DevToolsAgent.crashContext(limit: Int = 40): List<String> =
    listOf(portEvents, traffic, logs)
        .flatMap { stream -> stream.recentFacts().map { fact -> fact.sequence to render(stream, fact) } }
        .sortedBy { it.first }
        .takeLast(limit)
        .map { it.second }

private fun render(stream: FactStream, fact: AgentFact): String = when (fact) {
    is AgentFact.Log -> "[${fact.level}] ${fact.subsystem}: ${fact.message}"
    is AgentFact.Port -> "port ${fact.kind} ${fact.portKey}:${fact.portType}" +
        (fact.durationNanos?.let { " ${it / 1_000_000}ms" } ?: "") +
        (fact.failure?.let { " failed: $it" } ?: "")

    is AgentFact.Traffic -> "http ${fact.method.orEmpty()} ${fact.url} -> ${fact.statusCode ?: fact.failure} " +
        "${fact.durationMillis}ms cid=${fact.correlationId}"

    is AgentFact.Frame -> "frame ${fact.durationMillis}ms${if (fact.jank) " JANK" else ""}"
    is AgentFact.Memory -> "memory ${fact.usedBytes / 1024 / 1024}MiB of ${fact.totalBytes / 1024 / 1024}MiB"
    is AgentFact.Crash -> "crash ${fact.kind}: ${fact.message}"
}

/** Builds the report the store persists and the workbench replays. */
internal fun buildCrashReport(
    kind: String,
    message: String,
    stack: String,
    threadName: String,
    epochMillis: Long,
    context: List<String>,
): String = buildString {
    appendLine("kind=$kind")
    appendLine("thread=$threadName")
    appendLine("epochMillis=$epochMillis")
    appendLine("message=$message")
    appendLine("--- context ---")
    context.forEach { appendLine(it) }
    appendLine("--- stack ---")
    append(stack)
}

/** Parses a persisted report back into the fact the workbench receives. */
internal fun parseCrashReport(raw: String, sequence: Long, monotonicNanos: Long): AgentFact.Crash {
    fun field(name: String) = raw.lineSequence()
        .firstOrNull { it.startsWith("$name=") }?.substringAfter('=').orEmpty()
    val context = raw.substringAfter("--- context ---\n", "")
        .substringBefore("--- stack ---")
        .lines()
        .filter(String::isNotBlank)
    return AgentFact.Crash(
        sequence = sequence,
        monotonicNanos = monotonicNanos,
        epochMillis = field("epochMillis").toLongOrNull() ?: 0,
        kind = field("kind").ifBlank { "unknown" },
        message = field("message"),
        stack = raw.substringAfter("--- stack ---\n", ""),
        threadName = field("thread"),
        precedingFacts = context,
    )
}
