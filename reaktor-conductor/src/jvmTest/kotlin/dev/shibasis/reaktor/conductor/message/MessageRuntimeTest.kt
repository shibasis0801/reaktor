package dev.shibasis.reaktor.conductor.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.*
import org.koin.dsl.koinApplication
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class MessageRuntimeTest {
    private fun graph() = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}), label = "Messages test")
    private fun draft(id: String, recipient: MessageRecipient = MessageRecipient.Agent("osprey"), from: String = "tower",
        reply: String? = null) = SendMessage(id, from, recipient, MessageKind.Ask, "Inspect the memory receipt", replyTo = reply)

    @Test fun fanoutAndRetriesPreserveRecipientsAndIdempotency() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val graph = graph()
        try {
            var members = listOf("osprey", "falcon")
            val runtime = MessageRuntime(graph, SqliteObjectDatabase(driver, "messages-test"), { members }) { _, _ -> false }
            val request = draft("fanout", MessageRecipient.Plan("hangar:launch"))
            val first = runtime.api.send(request)
            members = listOf("argon")
            assertEquals(first, runtime.api.send(request))
            assertEquals(listOf("falcon", "osprey"), first.recipients)
            for (agent in first.recipients) assertEquals(first, runtime.api.inbox(InboxQuery(agent)).single().message)
            assertTrue(runtime.api.inbox(InboxQuery("argon")).isEmpty())
            assertFailsWith<IllegalArgumentException> { runtime.api.send(request.copy(body = "Different content")) }
            assertFailsWith<IllegalArgumentException> { runtime.api.read(MessageRef("argon", first.id)) }
            val ref = MessageRef("osprey", first.id)
            val acknowledged = runtime.api.ack(ref)
            assertNotNull(acknowledged.deliveredAt)
            assertNotNull(acknowledged.readAt)
            assertEquals(acknowledged, runtime.api.ack(ref))
            assertTrue(runtime.api.inbox(InboxQuery("osprey")).isEmpty())
            assertEquals(1, runtime.api.inbox(InboxQuery("osprey", unreadOnly = false)).size)
        } finally { graph.close(); driver.close() }
    }

    @Test fun durableMailboxRecoversAndOnlySuccessfulDeliveryProducesReceipt() = runBlocking {
        val path = Files.createTempFile("agent-messages", ".db")
        val firstDriver = JdbcSqliteDriver("jdbc:sqlite:$path")
        val firstGraph = graph()
        val message = try {
            val runtime = MessageRuntime(firstGraph, SqliteObjectDatabase(firstDriver, "messages-test"), { listOf("osprey") }) { _, _ -> false }
            runtime.api.send(draft("restart")).also {
                assertNull(runtime.api.read(MessageRef("osprey", it.id)).receipt.deliveredAt)
            }
        } finally { firstGraph.close(); firstDriver.close() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        val graph = graph()
        try {
            val deliveries = CopyOnWriteArrayList<String>()
            val runtime = MessageRuntime(graph, SqliteObjectDatabase(driver, "messages-test"), { error("Stored recipients must be used") }) { agent, value ->
                assertEquals("osprey", agent)
                deliveries += value.id
                deliveries.size > 1
            }
            val ref = MessageRef("osprey", message.id)
            withTimeout(15000) {
                while (runtime.api.read(ref).receipt.deliveredAt == null) delay(25)
            }
            assertEquals(listOf(message.id, message.id), deliveries.toList())
            val receipt = runtime.api.read(ref).receipt
            assertEquals(2, receipt.attempts)
            assertNull(receipt.readAt)
            assertEquals(message, runtime.api.inbox(InboxQuery("osprey")).single().message)
        } finally { graph.close(); driver.close(); Files.deleteIfExists(path) }
    }

    @Test fun aMessageStoredWithoutItsReceiptsIsEnqueuedAtStartup(): Unit = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val graph = graph()
        try {
            val database = SqliteObjectDatabase(driver, "messages-orphans")
            val orphan = MessageRef("osprey", "a".repeat(64))
            MessageStore(database).accept(Message(orphan.id, draft("orphan"), listOf(orphan.agent), System.currentTimeMillis()))
            val runtime = MessageRuntime(graph, database, { error("Stored recipients must be used") }) { _, _ -> false }
            withTimeout(15000) { while (runtime.store.receipt(orphan) == null) delay(25) }
            assertNull(runtime.store.receipt(orphan)?.readAt)
        } finally { graph.close(); driver.close() }
    }

    @Test fun pagesTraverseTheWholeMailboxAfterAcknowledgingTheAnchor(): Unit = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val graph = graph()
        try {
            val runtime = MessageRuntime(graph, SqliteObjectDatabase(driver, "messages-pages"),
                { listOf((it as MessageRecipient.Agent).key) }) { _, _ -> false }
            val sent = List(107) { runtime.api.send(draft("page-$it")) }
                .sortedWith(compareBy<Message> { it.sentAt }.thenBy { it.id })
            val foreign = runtime.api.send(draft("foreign", MessageRecipient.Agent("falcon")))
            val first = runtime.api.page(InboxQuery("osprey", limit = 17))
            assertEquals(sent.take(17), first.items.map { it.message })
            assertEquals(first.items, runtime.api.inbox(InboxQuery("osprey", limit = 17)))
            runtime.api.ack(MessageRef("osprey", requireNotNull(first.next)))
            val seen = first.items.toMutableList()
            var next = first.next
            while (next != null) {
                val page = runtime.api.page(InboxQuery("osprey", limit = 17, after = next))
                seen += page.items
                next = page.next
            }
            assertEquals(sent, seen.map { it.message })
            assertEquals(107, seen.map { it.message.id }.distinct().size)
            assertFailsWith<IllegalArgumentException> {
                runtime.api.page(InboxQuery("falcon", after = first.next))
            }
            assertFailsWith<IllegalArgumentException> {
                runtime.api.page(InboxQuery("osprey", after = foreign.id))
            }
            assertFailsWith<IllegalArgumentException> {
                runtime.api.page(InboxQuery("osprey", after = "0".repeat(64)))
            }
        } finally { graph.close(); driver.close() }
    }

    @Test fun conversationIsFilteredToTheReadersOwnMessages(): Unit = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val graph = graph()
        try {
            val runtime = MessageRuntime(graph, SqliteObjectDatabase(driver, "messages-test"), { listOf((it as MessageRecipient.Agent).key) }) { _, _ -> false }
            val question = runtime.api.send(draft("question"))
            val answer = runtime.api.send(draft("answer", MessageRecipient.Agent("tower"), "osprey", question.id))
            val privateReply = runtime.api.send(draft("private", MessageRecipient.Agent("falcon"), "tower", question.id))
            assertEquals(listOf(question, answer), runtime.api.thread(MessageRef("osprey", question.id)))
            assertEquals(listOf(privateReply), runtime.api.thread(MessageRef("falcon", privateReply.id)))
            assertFailsWith<IllegalArgumentException> {
                runtime.api.send(draft("forged-reply", MessageRecipient.Agent("osprey"), "argon", question.id))
            }
        } finally { graph.close(); driver.close() }
    }
}
