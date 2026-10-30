package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.auth.CredentialStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PlatformSecretBoxTest {
    private class ProtectedRecords : CredentialStore {
        val records = ConcurrentHashMap<String, ByteArray>()
        val created = AtomicInteger()
        override fun read(key: String) = records[key]?.copyOf()
        override fun write(key: String, value: ByteArray) { error("Root keys must never be overwritten") }
        override fun delete(key: String) { records.remove(key) }
        override fun create(key: String, value: ByteArray): Boolean =
            (records.putIfAbsent(key, value.copyOf()) == null).also { if (it) created.incrementAndGet() }
    }

    @Test fun freshInstancesOpenProtectedRecordsAndRejectEveryChangedBinding() = runBlocking {
        val records = ProtectedRecords()
        val type = SecretRecordType.MlsGroupStateSnapshot
        val plain = "private group state".encodeToByteArray()
        val aad = "tenant/app/device".encodeToByteArray()
        val sealed = DesktopPlatformSecretBox("test", records).seal(type, "chat/one", plain, aad)
        val reader = DesktopPlatformSecretBox("test", records)
        assertContentEquals(plain, reader.open(type, "chat/one", sealed, aad))
        assertFalse(sealed.ciphertext.contentEquals(plain))
        assertEquals(1, records.created.get())
        val changed = listOf(
            sealed.copy(version = 2), sealed.copy(algorithm = "other"), sealed.copy(keyAlias = "other"),
            sealed.copy(nonce = byteArrayOf(1)), sealed.copy(createdAtEpochMs = sealed.createdAtEpochMs + 1),
            sealed.copy(ciphertext = sealed.ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        )
        changed.forEach { assertFails { reader.open(type, "chat/one", it, aad) } }
        assertFails { reader.open(type, "chat/two", sealed, aad) }
        assertFails { reader.open(SecretRecordType.MlsDeviceIdentity, "chat/one", sealed, aad) }
        assertFails { reader.open(type, "chat/one", sealed, byteArrayOf()) }
        assertFails { reader.open(type, "chat/one", sealed, aad, SecretAccessOptions(requireUserPresence = true)) }
        assertContentEquals(plain, reader.open(type, "chat/one", sealed, aad))
        records.records.clear()
        assertFails { reader.open(type, "chat/one", sealed, aad) }
        assertEquals(1, records.created.get())
    }

    @Test fun simultaneousBoxInstancesKeepOneRootAndOpenEachOthersRecords() = runBlocking {
        val records = ProtectedRecords()
        val type = SecretRecordType.MlsDeviceIdentity
        val sealed = (0 until 32).map { index -> async(Dispatchers.Default) {
            DesktopPlatformSecretBox("race", records).seal(type, index.toString(), byteArrayOf(index.toByte()), byteArrayOf())
        } }.awaitAll()
        assertEquals(1, records.created.get())
        val reader = DesktopPlatformSecretBox("race", records)
        sealed.forEachIndexed { index, record ->
            assertContentEquals(byteArrayOf(index.toByte()), reader.open(type, index.toString(), record, byteArrayOf()))
        }
    }
}
