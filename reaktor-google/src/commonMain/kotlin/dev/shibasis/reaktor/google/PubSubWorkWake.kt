package dev.shibasis.reaktor.google

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.work.WorkHost
import dev.shibasis.reaktor.work.WorkScope

/** Timing stays with Quartz/Queues/alarms. Pub/Sub transports a wake and owns delivery retry/DLQ. */
class PubSubWorkWake(
    private val adapter: PubSubAdapter<*>,
    private val topic: PubSubTopic,
    private val subscription: PubSubSubscription,
    private val host: WorkHost,
) {
    suspend fun publish(scope: WorkScope): List<String> = adapter.publish(topic, PubSubMessage(
        json.encodeToString(WorkScope.serializer(), scope), attributes = mapOf(KindKey to Kind),
    )).getOrThrow()

    /** The configured host must reject foreign scopes and resolve current authority. */
    suspend fun consume(maxMessages: Int = 16): Int {
        require(maxMessages in 1..64)
        var acknowledged = 0
        adapter.pull(subscription, maxMessages).getOrThrow().forEach { message ->
            require(message.attributes[KindKey] == Kind) { "Unexpected Work wake envelope; configure broker DLQ" }
            val scope = json.decodeFromString(WorkScope.serializer(), message.data)
            val session = host.open(scope)
            try {
                session.runtime.drain()
                // Redelivery after an ACK failure is safe: the authoritative records decide execution.
                adapter.acknowledge(subscription, listOf(message.ackId)).getOrThrow()
                acknowledged++
            } finally { session.close() }
        }
        return acknowledged
    }
}

private const val KindKey = "reaktor-work-kind"
private const val Kind = "wake/v1"
