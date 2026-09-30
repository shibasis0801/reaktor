package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.Serializable

@Serializable
enum class PubSubReadAction { Topics, Subscriptions, Statistics, Recent }

@Serializable
data class PubSubQuery(val action: PubSubReadAction = PubSubReadAction.Subscriptions,
    val pageToken: String? = null, val subscription: String? = null,
    val metric: String = "num_undelivered_messages", val endTime: String? = null) {
    fun validate(): PubSubQuery {
        require(pageToken == null || (pageToken.length <= 4096 && pageToken.none(Char::isISOControl)))
        require(subscription == null || subscription.matches(Regex("[A-Za-z][A-Za-z0-9._~+%-]{2,254}")))
        require(metric in metrics)
        require(endTime == null || endTime.length <= 40)
        require(action != PubSubReadAction.Recent || subscription != null) { "Select an observed subscription" }
        return this
    }
    companion object {
        val metrics = setOf("num_undelivered_messages", "oldest_unacked_message_age", "unacked_bytes", "push_request_count", "ack_message_count")
    }
}
