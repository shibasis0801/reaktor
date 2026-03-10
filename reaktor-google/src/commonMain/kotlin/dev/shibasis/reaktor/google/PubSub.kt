package dev.shibasis.reaktor.google

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.serialization.Serializable

@Serializable
data class PubSubTopic(
    val projectId: String,
    val topicId: String,
) {
    val name: String
        get() = "projects/$projectId/topics/$topicId"
}

@Serializable
data class PubSubSubscription(
    val projectId: String,
    val subscriptionId: String,
) {
    val name: String
        get() = "projects/$projectId/subscriptions/$subscriptionId"
}

@Serializable
data class PubSubMessage(
    val data: String,
    val attributes: Map<String, String> = emptyMap(),
    val orderingKey: String? = null,
) {
    companion object {
        inline fun <reified T> json(
            value: T,
            attributes: Map<String, String> = emptyMap(),
            orderingKey: String? = null,
        ): PubSubMessage =
            PubSubMessage(
                data = json.encodeToString(kSerializer<T>(), value),
                attributes = attributes,
                orderingKey = orderingKey,
            )
    }
}

@Serializable
data class PulledPubSubMessage(
    val ackId: String,
    val messageId: String,
    val data: String,
    val attributes: Map<String, String> = emptyMap(),
    val orderingKey: String? = null,
    val publishTime: String? = null,
    val deliveryAttempt: Int? = null,
) {
    inline fun <reified T> decode(): T = json.decodeFromString(data)
}

@Serializable
data class PubSubSubscriptionOptions(
    val ackDeadlineSeconds: Int = 10,
)

fun interface GoogleAccessTokenProvider {
    suspend fun token(): String
}

abstract class PubSubAdapter<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller) {

    open val defaultProjectId: String? = null

    fun topic(
        topicId: String,
        projectId: String? = null,
    ): PubSubTopic = PubSubTopic(requireProjectId(projectId), topicId)

    fun subscription(
        subscriptionId: String,
        projectId: String? = null,
    ): PubSubSubscription = PubSubSubscription(requireProjectId(projectId), subscriptionId)

    abstract suspend fun ensureTopic(topic: PubSubTopic): Result<PubSubTopic>

    abstract suspend fun ensureSubscription(
        subscription: PubSubSubscription,
        topic: PubSubTopic,
        options: PubSubSubscriptionOptions = PubSubSubscriptionOptions(),
    ): Result<PubSubSubscription>

    abstract suspend fun publish(
        topic: PubSubTopic,
        messages: List<PubSubMessage>,
    ): Result<List<String>>

    suspend fun publish(
        topic: PubSubTopic,
        vararg messages: PubSubMessage,
    ): Result<List<String>> = publish(topic, messages.toList())

    abstract suspend fun pull(
        subscription: PubSubSubscription,
        maxMessages: Int = 1,
    ): Result<List<PulledPubSubMessage>>

    abstract suspend fun acknowledge(
        subscription: PubSubSubscription,
        ackIds: List<String>,
    ): Result<Unit>

    suspend fun acknowledge(
        subscription: PubSubSubscription,
        vararg ackIds: String,
    ): Result<Unit> = acknowledge(subscription, ackIds.toList())

    protected fun requireProjectId(projectId: String?): String =
        projectId
            ?: defaultProjectId
            ?: error("No GCP projectId was provided and this PubSubAdapter has no defaultProjectId")

    protected fun <T> unsupportedResult(message: String): Result<T> =
        Result.failure(UnsupportedOperationException(message))
}

var Feature.GooglePubSub by CreateSlot<PubSubAdapter<*>>()

suspend fun PubSubTopic.ensure(
    adapter: PubSubAdapter<*> = requirePubSubAdapter(),
): Result<PubSubTopic> = adapter.ensureTopic(this)

suspend fun PubSubSubscription.ensure(
    topic: PubSubTopic,
    adapter: PubSubAdapter<*> = requirePubSubAdapter(),
    options: PubSubSubscriptionOptions = PubSubSubscriptionOptions(),
): Result<PubSubSubscription> = adapter.ensureSubscription(this, topic, options)

suspend fun PubSubTopic.publish(
    adapter: PubSubAdapter<*> = requirePubSubAdapter(),
    vararg messages: PubSubMessage,
): Result<List<String>> = adapter.publish(this, *messages)

suspend fun PubSubSubscription.pull(
    maxMessages: Int = 1,
    adapter: PubSubAdapter<*> = requirePubSubAdapter(),
): Result<List<PulledPubSubMessage>> = adapter.pull(this, maxMessages)

suspend fun PubSubSubscription.acknowledge(
    adapter: PubSubAdapter<*> = requirePubSubAdapter(),
    vararg ackIds: String,
): Result<Unit> = adapter.acknowledge(this, *ackIds)

private fun requirePubSubAdapter(): PubSubAdapter<*> =
    Feature.GooglePubSub ?: error("Feature.GooglePubSub is not configured")
