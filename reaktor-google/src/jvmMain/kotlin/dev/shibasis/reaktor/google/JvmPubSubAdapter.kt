package dev.shibasis.reaktor.google

import com.google.api.gax.rpc.AlreadyExistsException
import com.google.cloud.pubsub.v1.Publisher
import com.google.cloud.pubsub.v1.SubscriptionAdminClient
import com.google.cloud.pubsub.v1.TopicAdminClient
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings
import com.google.protobuf.ByteString
import com.google.pubsub.v1.AcknowledgeRequest
import com.google.pubsub.v1.ProjectSubscriptionName
import com.google.pubsub.v1.ProjectTopicName
import com.google.pubsub.v1.PubsubMessage
import com.google.pubsub.v1.PullRequest
import com.google.pubsub.v1.PushConfig
import java.util.concurrent.TimeUnit

class JvmPubSubAdapter(
    override val defaultProjectId: String? = null,
) : PubSubAdapter<Unit>(Unit) {

    override suspend fun ensureTopic(topic: PubSubTopic): Result<PubSubTopic> =
        runCatching {
            TopicAdminClient.create().use { admin ->
                try {
                    admin.createTopic(ProjectTopicName.of(topic.projectId, topic.topicId))
                } catch (_: AlreadyExistsException) {
                    // Topic already exists; keep the API idempotent.
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
            SubscriptionAdminClient.create().use { admin ->
                try {
                    admin.createSubscription(
                        ProjectSubscriptionName.of(subscription.projectId, subscription.subscriptionId),
                        ProjectTopicName.of(topic.projectId, topic.topicId),
                        PushConfig.getDefaultInstance(),
                        options.ackDeadlineSeconds,
                    )
                } catch (_: AlreadyExistsException) {
                    // Subscription already exists; keep the API idempotent.
                }
            }
            subscription
        }

    override suspend fun publish(
        topic: PubSubTopic,
        messages: List<PubSubMessage>,
    ): Result<List<String>> =
        runCatching {
            val publisher = Publisher.newBuilder(ProjectTopicName.of(topic.projectId, topic.topicId)).build()
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
            GrpcSubscriberStub.create(SubscriberStubSettings.newBuilder().build()).use { subscriber ->
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
            GrpcSubscriberStub.create(SubscriberStubSettings.newBuilder().build()).use { subscriber ->
                subscriber.acknowledgeCallable().call(
                    AcknowledgeRequest.newBuilder()
                        .setSubscription(ProjectSubscriptionName.of(subscription.projectId, subscription.subscriptionId).toString())
                        .addAllAckIds(ackIds)
                        .build(),
                )
            }
        }
}
