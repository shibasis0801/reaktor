package dev.shibasis.reaktor.conductor.message

import dev.shibasis.reaktor.conductor.workspace.digest
import dev.shibasis.reaktor.graph.ActorTransition
import dev.shibasis.reaktor.graph.PersistentActorNode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.*
import dev.shibasis.reaktor.service.contract.Effect
import kotlin.time.Duration.Companion.seconds

internal sealed interface MailboxCommand {
    val id: String
    data class Enqueue(override val id: String) : MailboxCommand
    data class Delivered(override val id: String) : MailboxCommand
    data class Ack(override val id: String) : MailboxCommand
    data class Attempt(override val id: String) : MailboxCommand
}

internal sealed interface MailboxEnvelope {
    data class Change(val command: MailboxCommand, val reply: Reply<MessageReceipt>) : MailboxEnvelope
    data object Tick : MailboxEnvelope
    data class Result(val id: String, val delivered: Boolean) : MailboxEnvelope
}

internal fun mailboxKey(agent: String) = actorKey<MailboxEnvelope>("messages/${digest(agent)}")

internal class MailboxActor(graph: Graph, private val agent: String, private val store: MessageStore,
    deliver: suspend (String, Message) -> Boolean) : ActorNode<MailboxEnvelope>(graph, mailboxKey(agent)) {
    private val persistent = graph.Node { PersistentActorNode(it, "${key.name}/receipts", "Mailbox $agent", store.receipts.storeName,
        store.state(agent), setOf("ready"), listOf("enqueue", "delivered", "ack", "attempt").map {
            ActorTransition(it, setOf("ready"), setOf("ready"))
        }, { _: MailboxState -> "ready" }) }
    private val enqueue = persistent.transition<MessageReceipt>("enqueue", Effect.LocalArtifactWrite) { command, state ->
        val message = requireNotNull(store.get(command.id))
        require(agent in message.recipients)
        state.receipt ?: MessageReceipt(command.id, agent).also { state.receipt = it }
    }
    private val delivered = persistent.transition<MessageReceipt>("delivered", Effect.LocalArtifactWrite) { _, state ->
        val before = requireNotNull(state.receipt)
        if (before.deliveredAt != null) before else before.copy(deliveredAt = System.currentTimeMillis()).also { state.receipt = it }
    }
    private val acknowledge = persistent.transition<MessageReceipt>("ack", Effect.LocalArtifactWrite) { _, state ->
        val before = requireNotNull(state.receipt)
        val now = System.currentTimeMillis()
        if (before.readAt != null) before else before.copy(deliveredAt = before.deliveredAt ?: now, readAt = now).also { state.receipt = it }
    }
    private val attempt = persistent.transition<MessageReceipt>("attempt", Effect.LocalArtifactWrite) { _, state ->
        val before = requireNotNull(state.receipt)
        before.copy(attempts = before.attempts + 1, lastAttemptAt = System.currentTimeMillis()).also { state.receipt = it }
    }
    private val delivery = graph.Node { DeliveryActor(it, agent, this, deliver) }

    override suspend fun preStart() { every(1.seconds) { MailboxEnvelope.Tick } }

    override suspend fun receive(message: MailboxEnvelope) {
        when (message) {
            is MailboxEnvelope.Change -> message.reply.complete(when (message.command) {
                is MailboxCommand.Enqueue -> enqueue(message.command)
                is MailboxCommand.Delivered -> delivered(message.command)
                is MailboxCommand.Ack -> acknowledge(message.command)
                is MailboxCommand.Attempt -> attempt(message.command)
            })
            is MailboxEnvelope.Result -> if (message.delivered) delivered(MailboxCommand.Delivered(message.id))
            MailboxEnvelope.Tick -> store.inbox(InboxQuery(agent, limit = 100)).filter { it.receipt.deliveredAt == null }.forEach { pending ->
                val delay = minOf(60000L, 1000L shl minOf(pending.receipt.attempts, 6))
                if (System.currentTimeMillis() - (pending.receipt.lastAttemptAt ?: 0) >= delay) {
                    attempt(MailboxCommand.Attempt(pending.message.id))
                    delivery.send(pending.message)
                }
            }
        }
    }

    override suspend fun onFailure(message: MailboxEnvelope, error: Throwable) = ActorDirective.Restart
}

private class DeliveryActor(graph: Graph, private val agent: String, private val mailbox: ActorRef<MailboxEnvelope>,
    private val deliver: suspend (String, Message) -> Boolean) : ActorNode<Message>(graph, actorKey("message-delivery/${digest(agent)}")) {
    override suspend fun receive(message: Message) {
        val accepted = try { kotlinx.coroutines.withTimeoutOrNull(30000) { deliver(agent, message) } == true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        mailbox.send(MailboxEnvelope.Result(message.id, accepted))
    }
}
