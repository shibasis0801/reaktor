package dev.shibasis.reaktor.conductor.message

import dev.shibasis.reaktor.conductor.workspace.digest
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.*
import dev.shibasis.reaktor.graph.remote
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.over

class MessageRuntime(val graph: Graph, database: ObjectDatabase, private val resolve: suspend (MessageRecipient) -> List<String>,
    private val deliver: suspend (String, Message) -> Boolean) {
    val store = MessageStore(database)
    private val mailboxes = mutableMapOf<String, MailboxActor>()
    @Synchronized private fun mailbox(agent: String) = mailboxes.getOrPut(agent) {
        graph.provides(mailboxKey(agent)) { MailboxActor(it, agent, store, deliver) }
    }
    private suspend fun change(agent: String, command: MailboxCommand): MessageReceipt = mailbox(agent).ask { MailboxEnvelope.Change(command, it) }

    private val router = graph.provides(actorKey<Routing>("message-router")) { object : ActorNode<Routing>(it, actorKey("message-router")) {
        override suspend fun preStart() {
            store.unreceipted().forEach { change(it.agent, MailboxCommand.Enqueue(it.id)) }
            store.undelivered().forEach(::mailbox)
        }
        override suspend fun receive(message: Routing) {
            val draft = message.draft
            val id = digest("${draft.from}\u0000${draft.requestId}")
            val prior = store.get(id)
            val recipients = prior?.recipients ?: resolve(draft.to).distinct().sorted().also {
                require(it.isNotEmpty() && it.size <= 100 && it.all { agent -> agent.isNotBlank() && agent.length <= 2000 }) { "Message recipient has no agents" }
            }
            draft.replyTo?.let { reply ->
                val parent = requireNotNull(store.get(reply)) { "Unknown message being answered" }
                require(draft.from == parent.request.from || draft.from in parent.recipients) { "Reply author cannot read the parent message" }
            }
            val value = store.accept(Message(id, draft, recipients, System.currentTimeMillis()))
            value.recipients.forEach { change(it, MailboxCommand.Enqueue(value.id)) }
            message.reply.complete(value)
        }
        override suspend fun onFailure(message: Routing, error: Throwable) = ActorDirective.Restart
    } }

    private suspend fun read(ref: MessageRef): ReceivedMessage {
        val message = requireNotNull(store.get(ref.id)) { "Unknown message" }
        require(ref.agent in message.recipients) { "Message is outside this agent's mailbox" }
        return ReceivedMessage(message, requireNotNull(store.receipt(ref)))
    }

    private val server = object : MessageApi() {
        override val send = super.send.handle { draft -> router.ask<Message> { Routing(draft, it) } }
        override val inbox = super.inbox.handle(store::inbox)
        override val page = super.page.handle(store::page)
        override val read = super.read.handle { read(it) }
        override val ack = super.ack.handle { ref -> read(ref); change(ref.agent, MailboxCommand.Ack(ref.id)) }
        override val thread = super.thread.handle { ref ->
            val root = read(ref).message
            val all = store.all().associateBy { it.id }
            fun ancestor(message: Message): String {
                var current = message
                val visited = mutableSetOf<String>()
                while (current.request.replyTo != null && visited.add(current.id)) current = all[current.request.replyTo] ?: break
                return current.id
            }
            val thread = ancestor(root)
            all.values.filter { (it.request.from == ref.agent || ref.agent in it.recipients) && ancestor(it) == thread }
                .sortedWith(compareBy<Message> { it.sentAt }.thenBy { it.id })
        }
    }
    val served = graph.serve(server)
    val api = (MessageApi() over LocalLink(requireNotNull(served.served))).also { graph.remote(it) }
    suspend fun delivered(ref: MessageRef): MessageReceipt { read(ref); return change(ref.agent, MailboxCommand.Delivered(ref.id)) }
}

private data class Routing(val draft: SendMessage, val reply: Reply<Message>)
