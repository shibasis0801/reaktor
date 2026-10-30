package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.provide
import dev.shibasis.reaktor.graph.remote
import dev.shibasis.reaktor.db.core.MemoryObjectDatabase
import dev.shibasis.reaktor.db.core.ObjectStore
import dev.shibasis.reaktor.service.contract.*
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.service.SpanSink
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SecurityNodeTest {
    private class Box : PlatformSecretBox {
        val fail = AtomicBoolean()
        private val key = SecurityEngineFactory.storageKey()
        override suspend fun seal(recordType: SecretRecordType, recordId: String, plaintext: ByteArray, aad: ByteArray, options: SecretAccessOptions): SealedSecret {
            check(!fail.get()) { "Protected state write failed" }
            return sealSecret(key, "test-device", recordType, recordId, plaintext, aad)
        }
        override suspend fun open(recordType: SecretRecordType, recordId: String, sealed: SealedSecret, aad: ByteArray, options: SecretAccessOptions): ByteArray =
            openSecret(key, "test-device", recordType, recordId, sealed, aad)
    }

    private class Probe : Interceptor {
        override val sides = setOf(Side.Client)
        val lostCommitResponse = AtomicBoolean()
        val offline = AtomicBoolean()
        val publications = AtomicInteger()
        val consumptions = AtomicInteger()
        override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
            if (exchange.name == "publishCommit") publications.incrementAndGet()
            if (exchange.name == "consumeWelcomes") consumptions.incrementAndGet()
            if (offline.get()) throw Unavailable("Directory is offline")
            val result = next(exchange)
            if (exchange.name == "publishCommit" && lostCommitResponse.compareAndSet(true, false)) throw OutcomeUnknown("Response lost after acceptance")
            return result
        }
    }

    private class World {
        val credentials = SecurityTestCredentials()
        val spans = CopyOnWriteArrayList<ServiceSpan>()
        val state = MemorySecureDirectoryState()
        val members = mutableSetOf("alice", "bob", "carol", "dana")
        val authority = object : ConversationAuthority {
            override suspend fun isMember(conversationId: String, userId: String) = userId in members
            override suspend fun members(conversationId: String) = members.toSet()
        }
        fun client(user: String) = SecureDirectoryApi() over LocalLink(
            MemorySecureDirectory("tenant", "app", user, state, authority) { credentials.credential(user, it) })
        fun install(user: String, store: ObjectStore = MemoryObjectDatabase().store("security"), box: Box = Box(), probe: Probe = Probe(), resetIdentity: Boolean = false): Install =
            Install(this, user, store, box, probe, resetIdentity)
    }

    private class Install(val world: World, val user: String, val store: ObjectStore, val box: Box, val probe: Probe, resetIdentity: Boolean = false) : AutoCloseable {
        val graph = Graph(dispatcher = Dispatchers.Default, label = "security-test")
        val api: ReaktorSecurity
        init {
            graph.provide(SecuritySession("tenant", "app", user, world.credentials.policy.authKeys, resetIdentity))
            graph.provide<PlatformSecretBox>(box)
            graph.provide(store, "security-state")
            graph.remote(world.client(user), probe)
            val node = graph.Node { SecurityNode(it, SpanSink { span -> world.spans += span }) }
            graph.autoWire()
            api = node.security()
        }
        fun restart() = world.install(user, store, box, probe)
        override fun close() = graph.close()
    }

    @Test fun realActorsRecoverOrderingMembershipAndPendingPublicationAcrossRestarts() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        val installs = mutableListOf<Install>()
        fun install(user: String) = world.install(user).also { installs += it }
        try {
            val alice = install("alice")
            val bob = install("bob")
            val carol = install("carol")
            val dana = install("dana")
            for (device in listOf(alice, bob, carol, dana)) device.api.device()
            val first = coroutineScope { listOf(alice, bob).map { device -> async { device.api.reconcile("chat", world.members) } }.awaitAll() }
            assertTrue(first.all { it.epoch == 1L && it.firstJoinedEpoch == 1L && it.roster.size == 4 && it.health == ConversationHealth.Ready })
            carol.api.reconcile("chat", world.members)
            dana.api.reconcile("chat", world.members)
            val envelope = "chat/message-1".encodeToByteArray()
            val old = alice.api.seal("chat", envelope, "first encrypted message".encodeToByteArray())
            assertContentEquals("first encrypted message".encodeToByteArray(), bob.api.open("chat", envelope, old).plaintext)
            world.members -= setOf("carol", "dana")
            alice.probe.lostCommitResponse.set(true)
            assertFailsWith<OutcomeUnknown> { alice.api.reconcile("chat", world.members) }
            alice.close()
            val restarted = alice.restart().also { installs += it }
            val state = restarted.api.reconcile("chat", world.members)
            assertEquals(2L, state.epoch)
            assertEquals(setOf("alice", "bob"), state.roster.map { it.userId }.toSet())
            assertEquals(2, world.client("alice").commits(CommitCursor("chat")).size)
            val welcomeHistory = world.client("bob").commits(CommitCursor("chat"))
            assertEquals(setOf("alice", "bob", "carol", "dana"), welcomeHistory.first().members)
            assertEquals(world.members, welcomeHistory.last().members)
            bob.api.reconcile("chat", world.members)
            val fresh = restarted.api.seal("chat", envelope, "after removal".encodeToByteArray())
            assertContentEquals("after removal".encodeToByteArray(), bob.api.open("chat", envelope, fresh).plaintext)
            assertContentEquals("first encrypted message".encodeToByteArray(), restarted.api.open("chat", envelope, old).plaintext)
            assertFails { carol.api.open("chat", envelope, fresh) }
            val tablet = install("bob")
            val tabletId = tablet.api.device().credential.deviceId.value
            val joined = tablet.api.reconcile("chat", world.members)
            assertEquals(3L, joined.epoch)
            assertEquals(3L, joined.firstJoinedEpoch)
            assertTrue(joined.roster.any { it.deviceId == tabletId })
            restarted.api.reconcile("chat", world.members)
            val next = restarted.api.seal("chat", envelope, "new install".encodeToByteArray())
            assertContentEquals("new install".encodeToByteArray(), tablet.api.open("chat", envelope, next).plaintext)
            assertFailsWith<NativeSecurityException> { tablet.api.open("chat", envelope, old) }
            tablet.close()
            tablet.restart().use { restored ->
                assertFailsWith<NativeSecurityException> { restored.api.open("chat", envelope, old) }
                assertEquals(3L, restored.api.state("chat").first().firstJoinedEpoch)
            }
            assertTrue(world.spans.map { it.operation }.toSet().containsAll(setOf("enrol", "reconcile", "commit", "seal", "open")))
            assertTrue(world.spans.any { it.operation == "open" && it.status == 500 && it.decisions["failure"]?.startsWith("native_") == true })
            assertTrue(world.spans.all { it.contract == "reaktor.security" && it.durationMillis >= 0 })
            assertFalse(world.spans.joinToString().contains("first encrypted message"))
        } finally { installs.forEach { it.close() } }
    }

    @Test fun aRestartedDeviceOpensAcceptedHistoryWhileTheDirectoryIsOffline() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        world.install("alice").use { alice -> world.install("bob").use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "offline-history".encodeToByteArray()
            val message = alice.api.seal("chat", envelope, "available offline".encodeToByteArray())
            bob.close()
            bob.probe.offline.set(true)
            bob.restart().use { restarted ->
                assertContentEquals("available offline".encodeToByteArray(), restarted.api.open("chat", envelope, message).plaintext)
                assertFailsWith<Unavailable> { restarted.api.seal("chat", envelope, byteArrayOf(1)) }
                assertContentEquals("available offline".encodeToByteArray(), restarted.api.open("chat", envelope, message).plaintext)
                assertFails { restarted.api.open("chat", envelope, message.copy(senderDeviceId = "forged")) }
            }
        } }
    }

    @Test fun aRequestedDeviceResetCreatesOneFreshIdentityAndKeepsItOnRestart() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.install("alice").use { first ->
            val original = first.api.device().credential.deviceId
            first.close()
            world.install("alice", first.store, first.box, resetIdentity = true).use { reset ->
                val replacement = reset.api.device().credential.deviceId
                assertNotEquals(original, replacement)
                assertEquals(replacement, reset.api.device().credential.deviceId)
                reset.close()
                reset.restart().use { restored ->
                    assertEquals(replacement, restored.api.device().credential.deviceId)
                }
            }
        }
    }

    @Test fun protectedStateFailurePreventsPublicationAndWelcomeConsumption() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        world.install("alice").use { alice -> world.install("bob").use { bob ->
            alice.api.device()
            bob.api.device()
            alice.box.fail.set(true)
            assertFails { alice.api.reconcile("chat", world.members) }
            assertEquals(0, alice.probe.publications.get())
            alice.box.fail.set(false)
            alice.api.reconcile("chat", world.members)
            bob.box.fail.set(true)
            assertFails { bob.api.reconcile("chat", world.members) }
            assertEquals(0, bob.probe.consumptions.get())
            bob.box.fail.set(false)
            assertEquals(1L, bob.api.reconcile("chat", world.members).epoch)
            assertEquals(1, bob.probe.consumptions.get())
            val envelope = "protected-open".encodeToByteArray()
            val sealed = alice.api.seal("chat", envelope, "private".encodeToByteArray())
            bob.box.fail.set(true)
            assertFails { bob.api.open("chat", envelope, sealed) }
            bob.box.fail.set(false)
            val opened = bob.api.open("chat", envelope, sealed)
            try { assertEquals("private", opened.plaintext.decodeToString()) }
            finally { opened.plaintext.fill(0); opened.frankingKey.fill(0) }
        } }
    }
}

