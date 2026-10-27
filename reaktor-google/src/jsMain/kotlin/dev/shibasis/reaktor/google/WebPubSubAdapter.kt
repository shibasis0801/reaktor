package dev.shibasis.reaktor.google

import dev.shibasis.reaktor.auth.transport.AUTHORIZATION_HEADER
import dev.shibasis.reaktor.auth.transport.bearerAuthorization
import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlin.js.Promise
import kotlin.io.encoding.Base64

class WebPubSubAdapter(
    private val accessTokenProvider: GoogleAccessTokenProvider,
    override val defaultProjectId: String? = null,
    private val endpoint: String = "https://pubsub.googleapis.com/v1",
) : PubSubAdapter<Unit>(Unit) {

    override suspend fun ensureTopic(topic: PubSubTopic): Result<PubSubTopic> =
        runCatching {
            request(
                method = "PUT",
                path = topic.name,
                body = "{}",
                ignoredStatuses = setOf(409),
            )
            topic
        }

    override suspend fun ensureSubscription(
        subscription: PubSubSubscription,
        topic: PubSubTopic,
        options: PubSubSubscriptionOptions,
    ): Result<PubSubSubscription> =
        runCatching {
            request(
                method = "PUT",
                path = subscription.name,
                body = createSubscriptionBody(topic, options),
                ignoredStatuses = setOf(409),
            )
            subscription
        }

    override suspend fun publish(
        topic: PubSubTopic,
        messages: List<PubSubMessage>,
    ): Result<List<String>> =
        runCatching {
            val response =
                request(
                    method = "POST",
                    path = "${topic.name}:publish",
                    body =
                        json.encodeToString(
                            JsPublishRequest(
                                messages =
                                    messages.map { message ->
                                        JsPublishMessage(
                                            data = Base64.encode(message.data.encodeToByteArray()),
                                            attributes = message.attributes.takeIf { it.isNotEmpty() },
                                            orderingKey = message.orderingKey,
                                        )
                                    },
                            ),
                        ),
                )

            json.decodeFromString<JsPublishResponse>(response).messageIds
        }

    override suspend fun pull(
        subscription: PubSubSubscription,
        maxMessages: Int,
    ): Result<List<PulledPubSubMessage>> =
        runCatching {
            val response =
                request(
                    method = "POST",
                    path = "${subscription.name}:pull",
                    body = json.encodeToString(JsPullRequest(maxMessages = maxMessages)),
                )

            json.decodeFromString<JsPullResponse>(response)
                .receivedMessages
                .orEmpty()
                .map { received ->
                    val message = received.message ?: error("Pub/Sub pull response contained no message")
                    PulledPubSubMessage(
                        ackId = received.ackId ?: error("Pub/Sub pull response contained no ackId"),
                        messageId = message.messageId ?: error("Pub/Sub pull response contained no messageId"),
                        data = message.data?.let { Base64.decode(it).decodeToString() }.orEmpty(),
                        attributes = message.attributes.orEmpty(),
                        orderingKey = message.orderingKey,
                        publishTime = message.publishTime,
                        deliveryAttempt = received.deliveryAttempt,
                    )
                }
        }

    override suspend fun acknowledge(
        subscription: PubSubSubscription,
        ackIds: List<String>,
    ): Result<Unit> =
        runCatching {
            request(
                method = "POST",
                path = "${subscription.name}:acknowledge",
                body = json.encodeToString(JsAcknowledgeRequest(ackIds = ackIds)),
            )
        }

    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        ignoredStatuses: Set<Int> = emptySet(),
    ): String {
        val init = js("({})")
        init.method = method
        init.headers = js("({})")
        init.headers[AUTHORIZATION_HEADER] = bearerAuthorization(accessTokenProvider.token())
        if (body != null) {
            init.headers["Content-Type"] = "application/json"
            init.body = body
        }

        val response = fetch("$endpoint/$path", init).await()
        val text = response.text().await()
        if (!response.ok && response.status !in ignoredStatuses) {
            error("Google Pub/Sub request failed (${response.status}): $text")
        }
        return text
    }

    private fun createSubscriptionBody(
        topic: PubSubTopic,
        options: PubSubSubscriptionOptions,
    ): String =
        options.pushEndpoint
            ?.let { pushEndpoint ->
                json.encodeToString(
                    JsCreatePushSubscriptionRequest(
                        topic = topic.name,
                        pushConfig = JsPushConfig(pushEndpoint = pushEndpoint),
                        ackDeadlineSeconds = options.ackDeadlineSeconds,
                    ),
                )
            }
            ?: json.encodeToString(
                JsCreatePullSubscriptionRequest(
                    topic = topic.name,
                    ackDeadlineSeconds = options.ackDeadlineSeconds,
                ),
            )
}

@Serializable
private data class JsCreatePullSubscriptionRequest(
    val topic: String,
    val ackDeadlineSeconds: Int,
)

@Serializable
private data class JsCreatePushSubscriptionRequest(
    val topic: String,
    val pushConfig: JsPushConfig,
    val ackDeadlineSeconds: Int,
)

@Serializable
private data class JsPushConfig(
    val pushEndpoint: String,
)

@Serializable
private data class JsPublishRequest(
    val messages: List<JsPublishMessage>,
)

@Serializable
private data class JsPublishMessage(
    val data: String,
    val attributes: Map<String, String>? = null,
    val orderingKey: String? = null,
)

@Serializable
private data class JsPublishResponse(
    val messageIds: List<String>,
)

@Serializable
private data class JsPullRequest(
    val maxMessages: Int,
)

@Serializable
private data class JsPullResponse(
    val receivedMessages: List<JsReceivedMessage>? = null,
)

@Serializable
private data class JsReceivedMessage(
    val ackId: String? = null,
    val message: JsPulledMessage? = null,
    val deliveryAttempt: Int? = null,
)

@Serializable
private data class JsPulledMessage(
    val data: String? = null,
    val attributes: Map<String, String>? = null,
    val messageId: String? = null,
    val orderingKey: String? = null,
    val publishTime: String? = null,
)

@Serializable
private data class JsAcknowledgeRequest(
    val ackIds: List<String>,
)

private external fun fetch(
    input: String,
    init: dynamic = definedExternally,
): Promise<FetchResponse>

private external interface FetchResponse {
    val ok: Boolean
    val status: Int
    fun text(): Promise<String>
}

