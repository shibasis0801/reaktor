package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.io.network.HttpConditions
import dev.shibasis.reaktor.io.network.HttpExchange
import dev.shibasis.reaktor.io.network.HttpObservation
import dev.shibasis.reaktor.io.network.HttpObserver
import dev.shibasis.reaktor.io.network.SocketObservation
import kotlinx.coroutines.flow.update

class HttpTap(
    private val stream: FactStream,
    private val epochMillis: () -> Long,
) : HttpObserver {

    override fun observe(exchange: HttpExchange) {
        if (!stream.enabled) return
        val nowNanos = DevToolsClock.nanos()
        val nowEpoch = epochMillis()
        fun nanosAt(epoch: Long) = nowNanos - (nowEpoch - epoch) * 1_000_000
        val requestHeaders = exchange.requestHeaders.masked()
        stream.emit { sequence, nanos ->
            AgentFact.Traffic(
                sequence = sequence,
                monotonicNanos = nanos,
                correlationId = requestHeaders.entries
                    .firstOrNull { it.key.equals(DevToolsProtocol.CorrelationHeader, ignoreCase = true) }
                    ?.value ?: "http-$sequence",
                operation = exchange.url.redactedUrl().substringAfter("://").substringAfter('/', "").let { "/$it" },
                transport = "HTTP",
                method = exchange.method,
                url = exchange.url.redactedUrl().masked(),
                requestBytes = exchange.requestBytes,
                responseBytes = exchange.responseBytes,
                statusCode = exchange.statusCode,
                durationMillis = exchange.finishedAtMillis - exchange.startedAtMillis,
                failure = exchange.failure?.redacted(),
                requestHeaders = requestHeaders,
                responseHeaders = exchange.responseHeaders.masked(),
                startedNanos = nanosAt(exchange.startedAtMillis),
                respondedNanos = nanosAt(exchange.respondedAtMillis),
                source = "http",
                cancelled = exchange.cancelled,
            )
        }
    }
}

private fun List<Pair<String, String>>.masked(): Map<String, String> =
    groupBy({ it.first }, { it.second }).mapValues { (name, values) ->
        if (!name.equals("content-type", ignoreCase = true) &&
            !name.equals("content-length", ignoreCase = true) && sensitiveFieldName.containsMatchIn(name))
            "*** (${values.sumOf { it.length }} chars)"
        else values.joinToString(", ").masked()
    }

fun DevToolsAgent.instrumentHttp(): () -> Unit {
    val observing = HttpObservation.observe(HttpTap(traffic, ::epochMillis))
    val sockets = SocketObservation.observe { event ->
        if (!traffic.enabled) return@observe
        val ageNanos = (epochMillis() - event.atMillis).coerceAtLeast(0) * 1_000_000
        traffic.emit { sequence, nanos ->
            AgentFact.Socket(
                sequence = sequence,
                monotonicNanos = nanos - ageNanos,
                connection = event.connection,
                url = event.url.redactedUrl().masked(),
                event = event.kind.name.lowercase(),
                bytes = event.bytes,
                binary = event.binary,
                heartbeat = event.heartbeat,
                code = event.code,
            )
        }
    }
    val conditions = networkConditions()
    register(conditions)
    return {
        observing()
        sockets()
        unregister(conditions)
        HttpObservation.conditions.value = HttpConditions()
    }
}

private fun networkConditions(): CommandHandler =
    commandHandler(AgentCapability.FaultInjection, "network", "block", "unblock", "reset", "state") { command ->
        val arguments = command.arguments
        HttpObservation.conditions.update { current ->
            when (command.action) {
                "network" -> current.copy(
                    latencyMillis = arguments["latencyMillis"]?.toLongOrNull()?.coerceIn(0L, 60_000L) ?: current.latencyMillis,
                    failureRate = arguments["failureRate"]?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: current.failureRate,
                    offline = arguments["offline"]?.toBooleanStrictOrNull() ?: current.offline,
                )
                "block" -> current.copy(blocked = (current.blocked + arguments["pattern"].orEmpty()).filter(String::isNotBlank).distinct())
                "unblock" -> current.copy(blocked = current.blocked - arguments["pattern"].orEmpty())
                "reset" -> HttpConditions()
                else -> current
            }
        }
        val now = HttpObservation.conditions.value
        val state = NetworkConditions(now.latencyMillis, now.failureRate, now.offline, now.blocked)
        AgentCommandResult(command.id, true, state.summary(), json.encodeToString(NetworkConditions.serializer(), state))
    }

fun NetworkConditions.summary(): String = buildList {
    if (offline) add("Offline")
    if (latencyMillis > 0) add("+$latencyMillis ms")
    if (failureRate > 0.0) add("${(failureRate * 100).toInt()}% fail")
    if (blocked.isNotEmpty()) add("${blocked.size} blocked")
}.joinToString(" · ").ifEmpty { "Normal network" }
