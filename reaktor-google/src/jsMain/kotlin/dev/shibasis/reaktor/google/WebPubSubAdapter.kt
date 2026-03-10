package dev.shibasis.reaktor.google

import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

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
                body =
                    json.encodeToString(
                        JsCreateSubscriptionRequest(
                            topic = topic.name,
                            ackDeadlineSeconds = options.ackDeadlineSeconds,
                        ),
                    ),
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
                                            data = encodeBase64(message.data),
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
                        data = decodeBase64(message.data),
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
    ): String {
        val init = js("({})")
        init.method = method
        init.headers = js("({})")
        init.headers["Authorization"] = "Bearer ${accessTokenProvider.token()}"
        if (body != null) {
            init.headers["Content-Type"] = "application/json"
            init.body = body
        }

        val response = fetch("$endpoint/$path", init).await()
        val text = response.text().await()
        if (!response.ok) {
            error("Google Pub/Sub request failed (${response.status}): $text")
        }
        return text
    }
}

@Serializable
private data class JsCreateSubscriptionRequest(
    val topic: String,
    val ackDeadlineSeconds: Int,
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

private external fun btoa(value: String): String

private external fun atob(value: String): String

private external class TextEncoder {
    fun encode(value: String): Uint8Array
}

private external class TextDecoder {
    fun decode(value: Uint8Array): String
}

private fun encodeBase64(value: String): String {
    val bytes = TextEncoder().encode(value)
    val binary =
        CharArray(bytes.length) { index ->
            byteAt(bytes, index).toChar()
        }.concatToString()
    return btoa(binary)
}

private fun decodeBase64(value: String?): String {
    if (value.isNullOrEmpty()) {
        return ""
    }

    val binary = atob(value)
    val bytes = Uint8Array(binary.length)
    repeat(binary.length) { index ->
        setByte(bytes, index, binary[index].code)
    }
    return TextDecoder().decode(bytes)
}

private fun byteAt(
    bytes: Uint8Array,
    index: Int,
): Int = (bytes.asDynamic()[index] as Number).toInt()

private fun setByte(
    bytes: Uint8Array,
    index: Int,
    value: Int,
) {
    bytes.asDynamic()[index] = value
}
