package dev.shibasis.reaktor.conductor.message

import kotlinx.serialization.Serializable

@Serializable
enum class MessageKind { Note, Ask, Answer, Preempt, Handoff, Review }

@Serializable
sealed interface MessageRecipient {
    @Serializable data class Agent(val key: String) : MessageRecipient
    @Serializable data class Seat(val plan: String, val seat: String) : MessageRecipient
    @Serializable data class Plan(val thread: String) : MessageRecipient
}

@Serializable
data class SendMessage(val requestId: String, val from: String, val to: MessageRecipient, val kind: MessageKind, val body: String,
    val item: String? = null, val replyTo: String? = null) {
    init {
        require(requestId.isNotBlank() && requestId.length <= 200 && from.isNotBlank() && from.length <= 2000)
        require(body.isNotBlank() && body.encodeToByteArray().size <= 8192)
        require(item == null || item.length in 1..2000)
        require(replyTo == null || replyTo.matches(Regex("[a-f0-9]{64}")))
        when (to) {
            is MessageRecipient.Agent -> require(to.key.isNotBlank() && to.key.length <= 2000)
            is MessageRecipient.Plan -> require(to.thread.isNotBlank() && to.thread.length <= 2000)
            is MessageRecipient.Seat -> require(to.plan.isNotBlank() && to.plan.length <= 2000 && to.seat.isNotBlank() && to.seat.length <= 200)
        }
    }
}

@Serializable
data class Message(val id: String, val request: SendMessage, val recipients: List<String>, val sentAt: Long)

@Serializable
data class MessageReceipt(val message: String, val agent: String, val deliveredAt: Long? = null, val readAt: Long? = null,
    val attempts: Int = 0, val lastAttemptAt: Long? = null)

@Serializable
data class ReceivedMessage(val message: Message, val receipt: MessageReceipt)

@Serializable
data class MessageRef(val agent: String, val id: String) {
    init { require(agent.isNotBlank() && agent.length <= 2000 && id.matches(Regex("[a-f0-9]{64}"))) }
}

@Serializable
data class InboxQuery(val agent: String, val unreadOnly: Boolean = true, val limit: Int = 50, val after: String? = null) {
    init {
        require(agent.isNotBlank() && agent.length <= 2000 && limit in 1..100)
        require(after == null || after.matches(Regex("[a-f0-9]{64}")))
    }
}

@Serializable
data class InboxPage(val items: List<ReceivedMessage>, val next: String? = null)
