package dev.shibasis.reaktor.google

import com.google.api.gax.rpc.AlreadyExistsException
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.pubsub.v1.Publisher
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.cloud.pubsub.v1.TopicAdminSettings
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub
import dev.shibasis.reaktor.core.framework.json
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings
import com.google.protobuf.ByteString
import com.google.pubsub.v1.AcknowledgeRequest
import com.google.pubsub.v1.ProjectSubscriptionName
import com.google.pubsub.v1.ProjectTopicName
import com.google.pubsub.v1.PubsubMessage
import com.google.pubsub.v1.PullRequest
import com.google.pubsub.v1.PushConfig
import dev.shibasis.reaktor.auth.transport.AUTHORIZATION_HEADER
import dev.shibasis.reaktor.auth.transport.bearerAuthorization
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.TimeUnit

class JvmPubSubAdapter(
    override val defaultProjectId: String? = null,
    credentials: GoogleCredentials? = null,
) : PubSubAdapter<Unit>(Unit) {
    private val scopedCredentials = credentials?.createScoped(listOf(PUBSUB_SCOPE))
    private val credentialsProvider =
        scopedCredentials?.let(FixedCredentialsProvider::create)

    override suspend fun ensureTopic(topic: PubSubTopic): Result<PubSubTopic> =
        runCatching {
            if (scopedCredentials != null) {
                restRequest(
                    method = "PUT",
                    path = topic.name,
                    body = "{}",
                    ignoredStatuses = setOf(409),
                )
            } else {
                createTopicAdminClient().use { admin ->
                    try {
                        admin.createTopic(ProjectTopicName.of(topic.projectId, topic.topicId))
                    } catch (_: AlreadyExistsException) {
                        // Topic already exists; keep the API idempotent.
                    }
                }
            }
            topic
        }

    override suspend fun ensureSubscription(
        subscription: PubSubSubscription,
        topic: PubSubTopic,
        options: PubSubSubscriptionOptions,
    ): Result<PubSubSubscription> =
        runCatching {
            if (scopedCredentials != null) {
                val response =
                    restRequest(
                        method = "PUT",
                        path = subscription.name,
                        body = subscriptionRestBody(topic, options),
                        ignoredStatuses = setOf(409),
                    )

                if (response.statusCode == 409 && options.pushEndpoint != null) {
                    restRequest(
                        method = "PATCH",
                        path = "${subscription.name}?updateMask=pushConfig,ackDeadlineSeconds",
                        body = subscriptionRestBody(topic, options),
                    )
                }
            } else {
                createSubscriptionAdminClient().use { admin ->
                    val subscriptionName =
                        ProjectSubscriptionName.of(subscription.projectId, subscription.subscriptionId)
                    val pushConfig = options.toPushConfig()
                    try {
                        admin.createSubscription(
                            subscriptionName,
                            ProjectTopicName.of(topic.projectId, topic.topicId),
                            pushConfig,
                            options.ackDeadlineSeconds,
                        )
                    } catch (_: AlreadyExistsException) {
                        if (options.pushEndpoint != null) {
                            admin.modifyPushConfig(subscriptionName, pushConfig)
                        }
                    }
                }
            }
            subscription
        }

    override suspend fun publish(
        topic: PubSubTopic,
        messages: List<PubSubMessage>,
    ): Result<List<String>> =
        runCatching {
            if (scopedCredentials != null) {
                val response =
                    restRequest(
                        method = "POST",
                        path = "${topic.name}:publish",
                        body = publishRestBody(messages),
                    )
                return@runCatching json.decodeFromString<RestPublishResponse>(response.body).messageIds
            }

            val publisher =
                Publisher.newBuilder(ProjectTopicName.of(topic.projectId, topic.topicId))
                    .also { builder ->
                        credentialsProvider?.let(builder::setCredentialsProvider)
                    }
                    .build()
            try {
                messages.map { message ->
                    publisher.publish(
                        PubsubMessage.newBuilder()
                            .setData(ByteString.copyFromUtf8(message.data))
                            .putAllAttributes(message.attributes)
                            .apply {
                                message.orderingKey?.let(::setOrderingKey)
                            }
                            .build(),
                    ).get()
                }
            } finally {
                publisher.shutdown()
                publisher.awaitTermination(5, TimeUnit.SECONDS)
            }
        }

    override suspend fun pull(
        subscription: PubSubSubscription,
        maxMessages: Int,
    ): Result<List<PulledPubSubMessage>> =
        runCatching {
            createSubscriberStub().use { subscriber ->
                subscriber.pullCallable().call(
                    PullRequest.newBuilder()
                        .setSubscription(ProjectSubscriptionName.of(subscription.projectId, subscription.subscriptionId).toString())
                        .setMaxMessages(maxMessages)
                        .build(),
                ).receivedMessagesList.map { received ->
                    val message = received.message
                    PulledPubSubMessage(
                        ackId = received.ackId,
                        messageId = message.messageId,
                        data = message.data.toStringUtf8(),
                        attributes = message.attributesMap,
                        orderingKey = message.orderingKey.takeIf(String::isNotBlank),
                        publishTime = message.publishTime?.toString(),
                        deliveryAttempt = received.deliveryAttempt.takeIf { it > 0 },
                    )
                }
            }
        }

    override suspend fun acknowledge(
        subscription: PubSubSubscription,
        ackIds: List<String>,
    ): Result<Unit> =
        runCatching {
            createSubscriberStub().use { subscriber ->
                subscriber.acknowledgeCallable().call(
                    AcknowledgeRequest.newBuilder()
                        .setSubscription(ProjectSubscriptionName.of(subscription.projectId, subscription.subscriptionId).toString())
                        .addAllAckIds(ackIds)
                        .build(),
                )
            }
        }

    private fun createTopicAdminClient(): TopicAdminClient =
        credentialsProvider
            ?.let {
                TopicAdminClient.create(
                    TopicAdminSettings.newBuilder()
                        .setCredentialsProvider(it)
                        .build(),
                )
            }
            ?: TopicAdminClient.create()

    private fun createSubscriptionAdminClient(): SubscriptionAdminClient =
        credentialsProvider
            ?.let {
                SubscriptionAdminClient.create(
                    SubscriptionAdminSettings.newBuilder()
                        .setCredentialsProvider(it)
                        .build(),
                )
            }
            ?: SubscriptionAdminClient.create()

    private fun createSubscriberStub(): GrpcSubscriberStub =
        GrpcSubscriberStub.create(
            SubscriberStubSettings.newBuilder()
                .also { builder ->
                    credentialsProvider?.let(builder::setCredentialsProvider)
                }
                .build(),
        )

    private fun PubSubSubscriptionOptions.toPushConfig(): PushConfig =
        pushEndpoint
            ?.let { endpoint ->
                PushConfig.newBuilder()
                    .setPushEndpoint(endpoint)
                    .build()
            }
            ?: PushConfig.getDefaultInstance()

    private fun subscriptionRestBody(
        topic: PubSubTopic,
        options: PubSubSubscriptionOptions,
    ): String =
        buildString {
            append("{\"topic\":\"")
            append(topic.name.escapeJson())
            append("\",\"ackDeadlineSeconds\":")
            append(options.ackDeadlineSeconds)
            options.pushEndpoint?.let { endpoint ->
                append(",\"pushConfig\":{\"pushEndpoint\":\"")
                append(endpoint.escapeJson())
                append("\"}")
            }
            append("}")
        }

    private fun publishRestBody(messages: List<PubSubMessage>): String =
        json.encodeToString(
            RestPublishRequest(
                messages =
                    messages.map { message ->
                        RestPublishMessage(
                            data = Base64.getEncoder().encodeToString(message.data.encodeToByteArray()),
                            attributes = message.attributes.takeIf { it.isNotEmpty() },
                            orderingKey = message.orderingKey,
                        )
                    },
            ),
        )

    private fun restRequest(
        method: String,
        path: String,
        body: String,
        ignoredStatuses: Set<Int> = emptySet(),
    ): RestResponse {
        val credentials = scopedCredentials ?: error("REST Pub/Sub requests require explicit GoogleCredentials")
        credentials.refreshIfExpired()
        val token = credentials.accessToken?.tokenValue ?: run {
            credentials.refresh()
            credentials.accessToken.tokenValue
        }
        val response =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create("$PUBSUB_ENDPOINT/$path"))
                        .method(method, HttpRequest.BodyPublishers.ofString(body))
                        .header(AUTHORIZATION_HEADER, bearerAuthorization(token))
                        .header("Content-Type", "application/json")
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
        if (response.statusCode() !in 200..299 && response.statusCode() !in ignoredStatuses) {
            error("Google Pub/Sub request failed (${response.statusCode()}): ${response.body()}")
        }
        return RestResponse(response.statusCode(), response.body())
    }

    private fun String.escapeJson(): String =
        buildString {
            this@escapeJson.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }

    companion object {
        const val PUBSUB_SCOPE = "https://www.googleapis.com/auth/pubsub"
        const val PUBSUB_ENDPOINT = "https://pubsub.googleapis.com/v1"
    }
}

private data class RestResponse(
    val statusCode: Int,
    val body: String,
)

@Serializable
private data class RestPublishRequest(
    val messages: List<RestPublishMessage>,
)

@Serializable
private data class RestPublishMessage(
    val data: String,
    val attributes: Map<String, String>? = null,
    val orderingKey: String? = null,
)

@Serializable
private data class RestPublishResponse(
    val messageIds: List<String> = emptyList(),
)
