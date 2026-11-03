package dev.shibasis.reaktor.conductor.message

import dev.shibasis.reaktor.conductor.workspace.digest
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.graph.ActorStateStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MessageStore(val database: ObjectDatabase) {
    val messages = database.store("conductor.messages")
    val receipts = database.store("conductor.message.receipts")
    val events get() = database.events
    private val mailboxGate = Mutex()
    private var mailboxes: MutableMap<String, MutableMap<String, MessageReceipt>>? = null

    suspend fun get(id: String): Message? = messages.read<Message>(id)?.value
    suspend fun all(): List<Message> = messages.getAll<Message>().map { it.value }
    internal fun key(agent: String, id: String) = "${digest(agent)}/$id"
    suspend fun receipt(ref: MessageRef): MessageReceipt? = receipts.read<MessageReceipt>(key(ref.agent, ref.id))?.value

    internal suspend fun accept(value: Message): Message {
        val prior = get(value.id)
        if (prior != null) {
            require(prior.request == value.request) { "Message request id already has different content" }
            return prior
        }
        if (database.compareAndSet(messages.storeName, value.id, null, value, Message.serializer())) return value
        return requireNotNull(get(value.id)).also { require(it.request == value.request) }
    }

    suspend fun inbox(query: InboxQuery): List<ReceivedMessage> = page(query).items

    suspend fun page(query: InboxQuery): InboxPage {
        val anchor = query.after?.let { id ->
            requireNotNull(receipt(MessageRef(query.agent, id))) { "Unknown mailbox continuation" }
            requireNotNull(get(id)) { "Unknown mailbox continuation" }
        }
        val values = mailbox(query.agent)
            .filter { !query.unreadOnly || it.readAt == null }
            .mapNotNull { receipt -> get(receipt.message)?.let { ReceivedMessage(it, receipt) } }
            .filter { anchor == null || it.message.sentAt > anchor.sentAt || (it.message.sentAt == anchor.sentAt && it.message.id > anchor.id) }
            .sortedWith(compareBy<ReceivedMessage> { it.message.sentAt }.thenBy { it.message.id }).take(query.limit + 1)
        val items = values.take(query.limit)
        return InboxPage(items, items.lastOrNull()?.message?.id?.takeIf { values.size > query.limit })
    }

    suspend fun unreceipted(): List<MessageRef> {
        val sent = all()
        return indexed { held -> sent.flatMap { message -> message.recipients.filter { held[it]?.containsKey(message.id) != true }.map { MessageRef(it, message.id) } } }
    }

    suspend fun undelivered(): Set<String> = indexed { held -> held.filterValues { mailbox -> mailbox.values.any { it.deliveredAt == null } }.keys.toSet() }

    private suspend fun mailbox(agent: String): List<MessageReceipt> = indexed { it[agent]?.values?.toList().orEmpty() }

    private suspend fun <T> indexed(read: (Map<String, Map<String, MessageReceipt>>) -> T): T = mailboxGate.withLock {
        read(mailboxes ?: HashMap<String, MutableMap<String, MessageReceipt>>().also { loaded ->
            receipts.getAll<MessageReceipt>().forEach { loaded.getOrPut(it.value.agent) { HashMap() }[it.value.message] = it.value }
            mailboxes = loaded
        })
    }

    private suspend fun remember(written: MessageReceipt?) = mailboxGate.withLock {
        if (written == null) mailboxes = null
        else mailboxes?.getOrPut(written.agent) { HashMap() }?.put(written.message, written)
    }

    internal fun state(agent: String): ActorStateStore<MailboxCommand, MailboxState> = object : ActorStateStore<MailboxCommand, MailboxState> {
        private val gate = Mutex()
        override suspend fun <Out> transact(input: MailboxCommand, execute: suspend (MailboxState) -> Out): Out = gate.withLock {
            val id = input.id
            val before = receipt(MessageRef(agent, id))
            val state = MailboxState(before)
            val result = execute(state)
            val after = state.receipt
            if (after != null && after != before) {
                val written = database.compareAndSet(receipts.storeName, key(agent, id), before, after, MessageReceipt.serializer())
                remember(after.takeIf { written })
                check(written) { "Mailbox changed in another host; retry the message receipt" }
            }
            result
        }
    }
}

internal class MailboxState(var receipt: MessageReceipt?)
