package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MemorySecureDirectoryTest {
    @Test fun clientsShareOrderingAndOneUsePackagesWithoutCrossingOwnershipOrTenantBoundaries() = runTest {
        val state = MemorySecureDirectoryState()
        val members = mutableSetOf("alice", "bob")
        val authority = object : ConversationAuthority {
            override suspend fun isMember(conversationId: String, userId: String) = conversationId == "chat" && userId in members
            override suspend fun members(conversationId: String) = members.toSet()
        }
        fun client(user: String, tenant: String = "tenant") = SecureDirectoryApi() over LocalLink(
            MemorySecureDirectory(tenant, "app", user, state, authority) { byteArrayOf(7) })
        val alice = client("alice")
        val bob = client("bob")
        val lookup = DirectoryLookup("chat", members.toSet())
        val a = DeviceRegistration("alice-phone", ByteArray(32) { 1 }, 3, byteArrayOf(1))
        val b = DeviceRegistration("bob-phone", ByteArray(32) { 2 }, 3, byteArrayOf(2))
        alice.register(a); bob.register(b)
        assertTrue(runCatching { bob.register(a) }.isFailure)
        assertTrue(runCatching { client("outsider").devices(lookup) }.isFailure)
        assertTrue(client("alice", "other").devices(lookup).isEmpty())
        for ((api, device) in listOf(alice to a.deviceId, bob to b.deviceId)) {
            api.publishPackages(KeyPackagePublication(device, (1..3).map {
                PublishedKeyPackage("$device-$it", byteArrayOf(it.toByte()), lastResort = it == 3)
            }))
        }
        val claimed = coroutineScope { listOf(async { alice.claimPackages(lookup) }, async { bob.claimPackages(lookup) }).flatMap { it.await() } }
        assertEquals(4, claimed.map { it.reference }.toSet().size)
        assertTrue(claimed.none { it.lastResort })
        assertEquals(alice.claimPackages(lookup).map { it.reference }, bob.claimPackages(lookup).map { it.reference })
        val devices = setOf(a.deviceId, b.deviceId)
        fun proposal(sender: String, value: Byte) = CommitPublication("chat", sender, null, 1, byteArrayOf(value), byteArrayOf(value),
            devices, emptySet(), listOf(DirectoryWelcome("welcome-$sender", (devices - sender).single(), "chat", 1, byteArrayOf(value))))
        val pa = proposal(a.deviceId, 1)
        val pb = proposal(b.deviceId, 2)
        val accepted = coroutineScope { listOf(async { alice.publishCommit(pa) }, async { bob.publishCommit(pb) }).map { it.await() } }
        assertEquals(1, accepted.count { it.accepted })
        val winner = if (accepted.first().accepted) alice else bob
        val loser = if (accepted.first().accepted) bob else alice
        val publication = if (accepted.first().accepted) pa else pb
        assertTrue(winner.publishCommit(publication).accepted)
        assertEquals(1, loser.commits(CommitCursor("chat")).size)
        val welcome = loser.welcomes(publication.welcomes.single().deviceId).single()
        loser.consumeWelcomes(WelcomeConsumption(welcome.deviceId, setOf(welcome.id)))
        assertTrue(loser.welcomes(welcome.deviceId).isEmpty())
        val bytes = loser.groupInfo("chat").bytes!!
        bytes[0] = 42
        assertContentEquals(publication.groupInfo, loser.groupInfo("chat").bytes)
        alice.revoke(a.deviceId)
        assertEquals(listOf(b.deviceId), bob.devices(lookup).map { it.deviceId })
        assertTrue(runCatching { alice.publishPackages(KeyPackagePublication(a.deviceId, listOf(PublishedKeyPackage("revoked", byteArrayOf(1))))) }.isFailure)
    }
}
