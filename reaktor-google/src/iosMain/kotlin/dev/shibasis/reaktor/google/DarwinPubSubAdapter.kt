package dev.shibasis.reaktor.google

class DarwinPubSubAdapter : PubSubAdapter<Unit>(Unit) {

    private val unsupported =
        "Google Pub/Sub is not implemented for iOS in reaktor-google. Use the JVM or JS adapter instead."

    override suspend fun ensureTopic(topic: PubSubTopic): Result<PubSubTopic> =
        unsupportedResult(unsupported)

    override suspend fun ensureSubscription(
        subscription: PubSubSubscription,
        topic: PubSubTopic,
        options: PubSubSubscriptionOptions,
    ): Result<PubSubSubscription> =
        unsupportedResult(unsupported)

    override suspend fun publish(
        topic: PubSubTopic,
        messages: List<PubSubMessage>,
    ): Result<List<String>> =
        unsupportedResult(unsupported)

    override suspend fun pull(
        subscription: PubSubSubscription,
        maxMessages: Int,
    ): Result<List<PulledPubSubMessage>> =
        unsupportedResult(unsupported)

    override suspend fun acknowledge(
        subscription: PubSubSubscription,
        ackIds: List<String>,
    ): Result<Unit> =
        unsupportedResult(unsupported)
}
