package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.provide
import dev.shibasis.reaktor.graph.remote
import dev.shibasis.reaktor.db.core.MemoryObjectDatabase
import dev.shibasis.reaktor.db.core.ObjectStore
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.StoredObject
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
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

    private class FailingDatabase(private val backing: MemoryObjectDatabase = MemoryObjectDatabase()) : ObjectDatabase(backing.objectSerializer) {
        val writes = AtomicInteger()
        val failAt = AtomicInteger(-1)
        override suspend fun <T : Any> putRaw(storeName: String, key: String, value: T, serializer: KSerializer<T>): StoredObject<T> {
            check(writes.incrementAndGet() != failAt.get()) { "Durable state write failed" }
            return backing.put(storeName, key, value, serializer)
        }
        override suspend fun <T : Any> getRaw(storeName: String, key: String, type: KClass<T>, serializer: KSerializer<T>) =
            backing.get(storeName, key, type, serializer)
        override suspend fun <T : Any> getAllRaw(storeName: String, type: KClass<T>, serializer: KSerializer<T>) =
            backing.getAll(storeName, type, serializer)
        override suspend fun deleteRaw(storeName: String, key: String) = backing.delete(storeName, key)
        override suspend fun clearRaw(storeName: String) = backing.clear(storeName)
        override suspend fun clearRaw() = backing.clear()
    }

    private fun assertPlaintext(expected: String, opened: OpenedMessage) {
        try { assertEquals(expected, opened.plaintext.decodeToString()) }
        finally { opened.plaintext.fill(0); opened.frankingKey.fill(0) }
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

    @Test fun sealedMessagesSurviveASenderRestartBeforeAnyEchoOrOpen() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        world.install("alice").use { alice -> world.install("bob").use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "sender-crash".encodeToByteArray()
            val first = alice.api.seal("chat", envelope, "before restart".encodeToByteArray())
            alice.close()
            alice.probe.offline.set(true)
            alice.restart().use { restarted ->
                assertPlaintext("before restart", restarted.api.open("chat", envelope, first))
                restarted.probe.offline.set(false)
                val second = restarted.api.seal("chat", envelope, "after restart".encodeToByteArray())
                assertPlaintext("before restart", bob.api.open("chat", envelope, first))
                assertPlaintext("after restart", bob.api.open("chat", envelope, second))
                assertPlaintext("before restart", restarted.api.open("chat", envelope, first))
            }
        } }
    }

    @Test fun aFailedPostSealStoreWriteReturnsNoCiphertextAndRestoresDurableSenderState() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        val database = FailingDatabase()
        world.install("alice", database.store("security")).use { alice -> world.install("bob").use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "sender-store-failure".encodeToByteArray()
            val before = database.writes.get()
            database.failAt.set(before + 1)
            assertFails { alice.api.seal("chat", envelope, "must not be released".encodeToByteArray()) }
            assertEquals(before + 1, database.writes.get())
            database.failAt.set(-1)
            val recovered = alice.api.seal("chat", envelope, "recovered send".encodeToByteArray())
            assertPlaintext("recovered send", bob.api.open("chat", envelope, recovered))
            alice.close()
            alice.probe.offline.set(true)
            alice.restart().use { restarted ->
                assertPlaintext("recovered send", restarted.api.open("chat", envelope, recovered))
            }
        } }
    }

    @Test fun rejectedHistoryAndTamperedMessagesPreserveReadyMembership() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        world.install("alice").use { alice -> world.install("bob").use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "rejected-history".encodeToByteArray()
            val old = alice.api.seal("chat", envelope, "before joining".encodeToByteArray())
            world.install("bob").use { tablet ->
                tablet.api.device()
                val joined = tablet.api.reconcile("chat", world.members)
                assertEquals(2L, joined.firstJoinedEpoch)
                assertEquals(ConversationHealth.Ready, joined.health)
                assertFailsWith<NativeSecurityException> { tablet.api.open("chat", envelope, old) }
                assertEquals(joined, tablet.api.state("chat").first())
                val fresh = alice.api.seal("chat", envelope, "after joining".encodeToByteArray())
                val damaged = fresh.copy(ciphertext = fresh.ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                assertFailsWith<NativeSecurityException> { tablet.api.open("chat", envelope, damaged) }
                assertEquals(joined, tablet.api.state("chat").first())
                assertFails { tablet.api.open("chat", envelope, fresh.copy(senderDeviceId = "wrong-device")) }
                assertEquals(joined, tablet.api.state("chat").first())
                assertPlaintext("after joining", tablet.api.open("chat", envelope, fresh))
                val reply = tablet.api.seal("chat", envelope, "new device reply".encodeToByteArray())
                assertPlaintext("new device reply", alice.api.open("chat", envelope, reply))
                tablet.close()
                tablet.restart().use { restarted ->
                    val restored = restarted.api.reconcile("chat", world.members)
                    assertFailsWith<NativeSecurityException> { restarted.api.open("chat", envelope, old) }
                    assertEquals(restored, restarted.api.state("chat").first())
                    assertPlaintext("after joining", restarted.api.open("chat", envelope, fresh))
                    assertPlaintext("new device reply", restarted.api.open("chat", envelope, reply))
                }
            }
        } }
    }

    @Test fun aFailedProtectedWriteAfterOpenStillFailsClosed() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        val database = FailingDatabase()
        world.install("alice").use { alice -> world.install("bob", database.store("security")).use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "receiver-store-failure".encodeToByteArray()
            val sealed = alice.api.seal("chat", envelope, "withheld until durable".encodeToByteArray())
            val before = database.writes.get()
            database.failAt.set(before + 1)
            assertFails { bob.api.open("chat", envelope, sealed) }
            assertEquals(before + 1, database.writes.get())
            assertEquals(ConversationHealth.Failed, bob.api.state("chat").first().health)
            database.failAt.set(-1)
            bob.api.reconcile("chat", world.members)
            assertPlaintext("withheld until durable", bob.api.open("chat", envelope, sealed))
            bob.close()
            bob.restart().use { restarted ->
                assertPlaintext("withheld until durable", restarted.api.open("chat", envelope, sealed))
            }
        } }
    }

    @Test fun repeatedAcceptedHistoryNeedsNoNewProtectedWrite() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        val database = FailingDatabase()
        world.install("alice").use { alice -> world.install("bob", database.store("security")).use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "repeated-history".encodeToByteArray()
            val sealed = alice.api.seal("chat", envelope, "durable history".encodeToByteArray())
            assertPlaintext("durable history", bob.api.open("chat", envelope, sealed))
            val before = database.writes.get()
            database.failAt.set(before + 1)
            bob.box.fail.set(true)
            bob.probe.offline.set(true)
            repeat(30) {
                val opened = bob.api.open("chat", envelope, sealed)
                assertTrue(opened.authenticated)
                assertTrue(opened.duplicate)
                assertPlaintext("durable history", opened)
            }
            assertEquals(before, database.writes.get())
            bob.close()
            bob.restart().use { restarted ->
                assertPlaintext("durable history", restarted.api.open("chat", envelope, sealed))
                assertEquals(before, database.writes.get())
            }
        } }
    }


    @Test fun sharedReadOnlyKeyRingsOpenOldEpochsAndAnonymousAnswersWithoutDirectoryAccess() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        world.install("alice").use { alice -> world.install("bob").use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "notice-history".encodeToByteArray()
            val old = alice.api.seal("chat", envelope, "old epoch".encodeToByteArray())
            val anonymousEnvelope = "anonymous-answer".encodeToByteArray()
            val anonymous = alice.api.seal("chat", anonymousEnvelope, "anonymous habit".encodeToByteArray(), circleGame = true, anonymous = true)
            val sharedBox = Box()
            val session = SecuritySession("tenant", "app", "bob", world.credentials.policy.authKeys)
            val beforeRotation = bob.api.sealedKeyRing(sharedBox)
            world.members += "carol"
            world.install("carol").use { carol ->
                carol.api.device()
                alice.api.reconcile("chat", world.members)
                bob.api.reconcile("chat", world.members)
                val current = alice.api.seal("chat", envelope, "new epoch".encodeToByteArray())
                assertTrue(current.epoch > old.epoch)
                SealedKeyRing.restore(session, beforeRotation, sharedBox).use { reader ->
                    assertPlaintext("old epoch", reader.open("chat", envelope, old))
                    assertFails { reader.open("chat", envelope, current) }
                }
                val snapshot = bob.api.sealedKeyRing(sharedBox)
                val state = bob.api.state("chat").first()
                val publications = bob.probe.publications.get()
                val consumptions = bob.probe.consumptions.get()
                bob.probe.offline.set(true)
                val reader = SealedKeyRing.restore(session, snapshot, sharedBox)
                reader.use {
                    assertPlaintext("old epoch", it.open("chat", envelope, old))
                    assertPlaintext("new epoch", it.open("chat", envelope, current))
                    val opened = it.open("chat", anonymousEnvelope, anonymous, circleGame = true)
                    assertFalse(opened.authenticated)
                    assertNull(opened.sender)
                    assertPlaintext("anonymous habit", opened)
                    assertFails { it.open("other-chat", envelope, current) }
                    assertFails { it.open("chat", "wrong-envelope".encodeToByteArray(), current) }
                    assertFails { it.open("chat", envelope, current.copy(senderDeviceId = "forged")) }
                    assertFails { it.open("chat", envelope, current.copy(ciphertext = current.ciphertext.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() })) }
                    assertFails { it.open("chat", anonymousEnvelope, anonymous) }
                    assertPlaintext("new epoch", it.open("chat", envelope, current))
                }
                assertFails { reader.open("chat", envelope, current) }
                assertEquals(state, bob.api.state("chat").first())
                assertEquals(publications, bob.probe.publications.get())
                assertEquals(consumptions, bob.probe.consumptions.get())
                assertFails { SealedKeyRing.restore(session.copy(userId = "alice"), snapshot, sharedBox) }
                assertFails { SealedKeyRing.restore(session.copy(appId = "other-app"), snapshot, sharedBox) }
                assertFails { SealedKeyRing.restore(session, snapshot, Box()) }
                assertFails { SealedKeyRing.restore(session, snapshot.copy(ciphertext = snapshot.ciphertext.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() }), sharedBox) }
            }
        } }
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

    @Test fun queuedOpensShareProtectedWritesAndStayDurable() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        val database = FailingDatabase()
        world.install("alice").use { alice -> world.install("bob", database.store("security")).use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "queued-opens".encodeToByteArray()
            val sealed = List(24) { alice.api.seal("chat", envelope, "message $it".encodeToByteArray()) }
            val before = database.writes.get()
            val opened = sealed.map { async { bob.api.open("chat", envelope, it) } }.awaitAll()
            opened.forEachIndexed { index, message ->
                assertFalse(message.duplicate)
                assertPlaintext("message $index", message)
            }
            assertTrue(database.writes.get() - before < sealed.size / 2)
            bob.close()
            bob.restart().use { restarted ->
                sealed.forEachIndexed { index, message ->
                    val again = restarted.api.open("chat", envelope, message)
                    assertTrue(again.duplicate)
                    assertPlaintext("message $index", again)
                }
            }
        } }
    }

    @Test fun aFailedWriteWithholdsEveryQueuedOpen() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        val database = FailingDatabase()
        world.install("alice").use { alice -> world.install("bob", database.store("security")).use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "withheld-queue".encodeToByteArray()
            val sealed = List(16) { alice.api.seal("chat", envelope, "held $it".encodeToByteArray()) }
            database.failAt.set(database.writes.get() + 1)
            val results = sealed.map { async { runCatching { bob.api.open("chat", envelope, it) } } }.awaitAll()
            assertTrue(results.any { it.isFailure })
            val released = results.mapIndexedNotNull { index, result -> result.getOrNull()?.let { opened ->
                assertPlaintext("held $index", opened)
                index
            } }
            bob.close()
            bob.restart().use { restarted ->
                sealed.forEachIndexed { index, message ->
                    val again = restarted.api.open("chat", envelope, message)
                    assertEquals(index in released, again.duplicate)
                    assertPlaintext("held $index", again)
                }
            }
        } }
    }

    @Test fun aRejectedOpenInsideAQueueReleasesTheOthers() = runBlocking<Unit>(Dispatchers.Default) {
        Reaktor.web()
        val world = World()
        world.members.retainAll(setOf("alice", "bob"))
        world.install("alice").use { alice -> world.install("bob").use { bob ->
            alice.api.device()
            bob.api.device()
            alice.api.reconcile("chat", world.members)
            bob.api.reconcile("chat", world.members)
            val envelope = "rejected-in-queue".encodeToByteArray()
            val sealed = List(12) { alice.api.seal("chat", envelope, "kept $it".encodeToByteArray()) }
            val damaged = sealed[6].copy(ciphertext = sealed[6].ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            val queue = sealed.take(6) + damaged + sealed.drop(7)
            val results = queue.map { async { runCatching { bob.api.open("chat", envelope, it) } } }.awaitAll()
            assertTrue(results[6].exceptionOrNull() is NativeSecurityException)
            results.forEachIndexed { index, result -> if (index != 6) assertPlaintext("kept $index", result.getOrThrow()) }
            assertEquals(ConversationHealth.Ready, bob.api.state("chat").first().health)
            bob.close()
            bob.restart().use { restarted ->
                sealed.forEachIndexed { index, message ->
                    val again = restarted.api.open("chat", envelope, message)
                    assertEquals(index != 6, again.duplicate)
                    assertPlaintext("kept $index", again)
                }
            }
        } }
    }
}

