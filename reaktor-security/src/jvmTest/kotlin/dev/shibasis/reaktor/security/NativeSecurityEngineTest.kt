package dev.shibasis.reaktor.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.system.measureNanoTime

class NativeSecurityEngineTest {
    private class Device(name: String) : AutoCloseable {
        var engine = NativeSecurityEngine.create(name.encodeToByteArray())
        private val key = NativeSecurityEngine.storageKey()
        private val aad = name.encodeToByteArray()
        private val file = Files.createTempFile("rsec-restart-", ".sealed")

        fun restart() {
            val snapshot = engine.snapshot()
            try { Files.write(file, NativeSecurityEngine.sealStorage(key, aad, snapshot)) }
            finally { snapshot.fill(0) }
            engine.close()
            val restored = NativeSecurityEngine.openStorage(key, aad, Files.readAllBytes(file))
            try { engine = NativeSecurityEngine.restore(restored) }
            finally { restored.fill(0) }
        }

        override fun close() {
            engine.close()
            key.fill(0)
            Files.deleteIfExists(file)
        }
    }

    @Test fun restartPreservesIdentityPendingCommitsHistoryAndMembership() {
        Device("alice").use { alice -> Device("bob").use { bob -> Device("carol").use { carol ->
            val chat = "chat-\u03bb"
            val identity = bob.engine.publicKey()
            val packageBytes = bob.engine.keyPackage()
            bob.restart()
            assertTrue(identity.contentEquals(bob.engine.publicKey()), "Identity must survive restart")
            val created = alice.engine.prepareCreate(chat, listOf(packageBytes))
            alice.restart()
            assertFailsWith<NativeSecurityException> { alice.engine.epoch(chat) }
            assertFailsWith<NativeSecurityException> { alice.engine.prepareCreate(chat, listOf(packageBytes)) }
            alice.engine.accept(chat, created.commit)
            bob.engine.join(chat, created.welcome)
            val envelope = byteArrayOf(1, 2, 3)
            val body = byteArrayOf(4, 5, 6)
            val old = alice.engine.seal(chat, envelope, body)
            alice.restart()
            bob.restart()
            val opened = bob.engine.open(chat, envelope, old)
            assertTrue(opened.plaintext.contentEquals(body), "Stored content must open after restart")
            assertTrue(opened.authenticated)
            assertFalse(opened.duplicate)
            bob.restart()
            assertTrue(bob.engine.open(chat, envelope, old).duplicate)
            val added = alice.engine.prepareAdd(chat, listOf(carol.engine.keyPackage()))
            alice.restart()
            alice.engine.accept(chat, added.commit)
            assertTrue(bob.engine.process(chat, added.commit))
            carol.engine.join(chat, added.welcome)
            assertFailsWith<NativeSecurityException> { carol.engine.open(chat, envelope, old) }
            val removed = alice.engine.prepareRemove(chat, 2)
            alice.engine.accept(chat, removed.commit)
            bob.engine.process(chat, removed.commit)
            alice.restart()
            val reply = bob.engine.seal(chat, envelope, body)
            assertTrue(alice.engine.open(chat, envelope, reply).plaintext.contentEquals(body), "Sending must continue after membership changes")
            assertFailsWith<NativeSecurityException> { carol.engine.open(chat, envelope, reply) }
            assertTrue(alice.engine.open(chat, envelope, old).plaintext.contentEquals(body), "Old epochs must remain readable")
        } } }
    }

    @Test fun conflictingCommitsAndTamperedRecordsLeaveTheAcceptedStateIntact() {
        Device("alice").use { alice -> Device("bob").use { bob ->
            val created = alice.engine.prepareCreate("chat", listOf(bob.engine.keyPackage()))
            alice.engine.accept("chat", created.commit)
            bob.engine.join("chat", created.welcome)
            val first = alice.engine.prepareRotate("chat")
            val conflict = bob.engine.prepareRotate("chat")
            assertEquals(1L, alice.engine.epoch("chat"))
            assertEquals(1L, bob.engine.epoch("chat"))
            alice.engine.accept("chat", first.commit)
            bob.engine.discard("chat")
            bob.engine.process("chat", first.commit)
            assertFailsWith<NativeSecurityException> { bob.engine.accept("chat", conflict.commit) }
            val sealed = alice.engine.seal("chat", byteArrayOf(), byteArrayOf(1))
            val changed = sealed.copy(ciphertext = sealed.ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertFailsWith<NativeSecurityException> { bob.engine.open("chat", byteArrayOf(), changed) }
            assertFalse(bob.engine.open("chat", byteArrayOf(), sealed).duplicate)
            assertEquals(2L, bob.engine.epoch("chat"))
        } }
    }

    @Test fun keyRingReaderOpensSealedHistoryAfterTheEngineHasClosed() {
        val key = NativeSecurityEngine.storageKey()
        val aad = byteArrayOf(5)
        val file = Files.createTempFile("rsec-key-ring-", ".sealed")
        try {
            val sealed = NativeSecurityEngine.create(byteArrayOf(1)).use { device ->
                val created = device.prepareCreate("chat", emptyList())
                device.accept("chat", created.commit)
                val ring = device.exportKeyRing()
                try { Files.write(file, NativeSecurityEngine.sealStorage(key, aad, ring)) }
                finally { ring.fill(0) }
                device.seal("chat", aad, byteArrayOf(9))
            }
            val ring = NativeSecurityEngine.openStorage(key, aad, Files.readAllBytes(file))
            val reader = try { NativeSecurityEngine.keyRing(ring) } finally { ring.fill(0) }
            reader.use {
                assertTrue(it.open("chat", aad, sealed).plaintext.contentEquals(byteArrayOf(9)))
                assertFalse(it.open("chat", aad, sealed).duplicate)
                assertFailsWith<NativeSecurityException> { it.open("other", aad, sealed) }
            }
            assertFailsWith<IllegalStateException> { reader.open("chat", aad, sealed) }
        } finally {
            key.fill(0)
            Files.deleteIfExists(file)
        }
    }

    @Test fun pinnedPolicySurvivesRestartAndUnregisteredDevicesCannotPublish() {
        val pin = "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val policy = EnginePolicy("tenant", "app", mapOf("auth-1" to pin))
        val snapshot = NativeSecurityEngine.create(policy).use { device ->
            assertFailsWith<NativeSecurityException> { device.keyPackage() }
            assertFailsWith<NativeSecurityException> { device.installCredential(byteArrayOf(0)) }
            device.setMembers("chat", setOf("alice", "bob"))
            device.snapshot()
        }
        try {
            assertFailsWith<NativeSecurityException> { NativeSecurityEngine.restore(snapshot) }
            NativeSecurityEngine.restore(snapshot, policy).use { device ->
                assertFailsWith<NativeSecurityException> { device.keyPackage() }
                assertFailsWith<NativeSecurityException> { device.lastResortKeyPackage() }
            }
        } finally { snapshot.fill(0) }
    }

    @Test fun externalJoinSurvivesRestartAndClosedHandlesAreRefused() {
        Device("alice").use { alice -> Device("bob").use { bob ->
            val created = alice.engine.prepareCreate("chat", emptyList())
            alice.engine.accept("chat", created.commit)
            val joined = bob.engine.prepareExternalJoin("chat", created.groupInfo)
            bob.restart()
            bob.engine.accept("chat", joined.commit)
            alice.engine.process("chat", joined.commit)
            val sealed = bob.engine.seal("chat", byteArrayOf(), byteArrayOf(7))
            assertTrue(alice.engine.open("chat", byteArrayOf(), sealed).authenticated)
            bob.engine.close()
            bob.engine.close()
            assertFailsWith<IllegalStateException> { bob.engine.snapshot() }
        } }
    }

    @Test fun nativeTimingEvidenceForSealOpenCachedHistoryAndEngineStart() {
        val chatId = "native-performance"
        val envelope = byteArrayOf(1, 2, 3)
        val alice = NativeSecurityEngine.create(byteArrayOf(1))
        val bob = NativeSecurityEngine.create(byteArrayOf(2))
        try {
            val created = alice.prepareCreate(chatId, emptyList())
            alice.accept(chatId, created.commit)
            val joined = bob.prepareExternalJoin(chatId, created.groupInfo)
            bob.accept(chatId, joined.commit)
            alice.process(chatId, joined.commit)

            val warmup = ArrayList<EngineSealed>(50)
            repeat(50) { index ->
                val sealed = alice.seal(chatId, envelope, byteArrayOf(index.toByte()))
                warmup += sealed
                val opened = bob.open(chatId, envelope, sealed)
                opened.plaintext.fill(0)
                opened.frankingKey.fill(0)
                opened.credential.fill(0)
            }
            warmup.forEach {
                it.header.fill(0)
                it.commitment.fill(0)
                it.ciphertext.fill(0)
            }

            val sealSamples = LongArray(500)
            val sealedMessages = ArrayList<EngineSealed>(sealSamples.size)
            repeat(sealSamples.size) { index ->
                var sealed: EngineSealed? = null
                sealSamples[index] = measureNanoTime {
                    sealed = alice.seal(chatId, envelope, byteArrayOf(index.toByte()))
                }
                sealedMessages += checkNotNull(sealed)
            }

            val openSamples = LongArray(sealedMessages.size)
            repeat(sealedMessages.size) { index ->
                var opened: EngineOpened? = null
                openSamples[index] = measureNanoTime {
                    opened = bob.open(chatId, envelope, sealedMessages[index])
                }
                checkNotNull(opened).also {
                    it.plaintext.fill(0)
                    it.frankingKey.fill(0)
                    it.credential.fill(0)
                }
            }

            val ring = bob.exportKeyRing()
            try {
                val history = sealedMessages.take(30)
                val historySamples = LongArray(20)
                repeat(historySamples.size) { sample ->
                    val opened = ArrayList<EngineOpened>(history.size)
                    historySamples[sample] = measureNanoTime {
                        NativeSecurityEngine.keyRing(ring).use { reader ->
                            history.forEach { opened += reader.open(chatId, envelope, it) }
                        }
                    }
                    opened.forEach {
                        it.plaintext.fill(0)
                        it.frankingKey.fill(0)
                        it.credential.fill(0)
                    }
                }

                val startSamples = LongArray(50)
                repeat(startSamples.size) { index ->
                    var engine: NativeSecurityEngine? = null
                    startSamples[index] = measureNanoTime {
                        engine = NativeSecurityEngine.create(byteArrayOf(index.toByte()))
                    }
                    checkNotNull(engine).close()
                }

                fun percentile(values: LongArray, percentile: Int): Long =
                    values.sortedArray()[((values.size * percentile + 99) / 100 - 1).coerceAtLeast(0)]

                System.out.println(
                    "RSEC_NATIVE_TIMING target=jvm-host-jna warmup=50 seal_samples=" + sealSamples.size +
                        " seal_p50_ns=" + percentile(sealSamples, 50) +
                        " seal_p95_ns=" + percentile(sealSamples, 95) +
                        " seal_max_ns=" + sealSamples.maxOrNull() +
                        " open_samples=" + openSamples.size +
                        " open_p50_ns=" + percentile(openSamples, 50) +
                        " open_p95_ns=" + percentile(openSamples, 95) +
                        " open_max_ns=" + openSamples.maxOrNull() +
                        " cached_batches=" + historySamples.size +
                        " cached_messages=30" +
                        " cached_p50_ns=" + percentile(historySamples, 50) +
                        " cached_p95_ns=" + percentile(historySamples, 95) +
                        " cached_max_ns=" + historySamples.maxOrNull() +
                        " start_samples=" + startSamples.size +
                        " start_p50_ns=" + percentile(startSamples, 50) +
                        " start_p95_ns=" + percentile(startSamples, 95) +
                        " start_max_ns=" + startSamples.maxOrNull()
                )
            } finally {
                ring.fill(0)
                sealedMessages.forEach {
                    it.header.fill(0)
                    it.commitment.fill(0)
                    it.ciphertext.fill(0)
                }
            }
        } finally {
            bob.close()
            alice.close()
        }
    }
}
