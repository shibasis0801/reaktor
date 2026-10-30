@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.cloudflare.*
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.promise
import kotlin.test.*

class SecureDirectorySqlTest {
    private val schema: String get() = js("process.env.RSEC_SQL_SCHEMA") as String
    private val url: String? get() = js("process.env.RSEC_SQL_TEST_URL") as? String
    private val chat = "00000000-0000-0000-0000-000000000001"
    private val binding = postgres("rsec_test")
    private val members = mutableSetOf("alice", "bob")
    private val notices = mutableListOf<DirectoryCommit>()

    private suspend fun <T> database(block: suspend PostgresDatabase.() -> T): T {
        val connection = requireNotNull(url)
        require(connection.contains("kznchykisuroalxnbpyn") || connection.contains("@127.0.0.1:"))
        require(Regex("rsec_probe_[a-f0-9]{32}").matches(schema))
        val env: dynamic = js("({rsec_test:{}})")
        env.rsec_test.connectionString = connection
        return cloudflareContext(env).postgres(binding, block = {
            rawRows("SET search_path TO \"$schema\"")
            block()
        })
    }

    private fun client(user: String, tenant: String = "tenant"): SecureDirectoryApi {
        val server = object : PostgresSecureDirectory() {
            override suspend fun <T> authenticated(block: suspend DirectorySession.() -> T): T = database {
                fun authority(database: PostgresDatabase): ConversationAuthority = object : ConversationAuthority {
                    override suspend fun isMember(conversationId: String, userId: String) = database.string("user_id",
                        "SELECT user_id FROM chat_members WHERE chat_id::text = %V AND user_id = %V AND left_at IS NULL",
                        conversationId, userId) != null
                    override suspend fun members(conversationId: String) = database.rawRows(
                        "SELECT user_id FROM chat_members WHERE chat_id::text = %V AND left_at IS NULL", conversationId)
                        .map { it.requireString("user_id") }.toSet()
                }
                DirectorySession(this, tenant, "app", user, ::authority).block()
            }
            override suspend fun enroll(registration: DeviceRegistration) = ByteArray(96) { 7 }
            override suspend fun announce(commit: DirectoryCommit, welcomes: List<DirectoryWelcome>) { notices += commit }
        }
        return SecureDirectoryApi() over LocalLink(server)
    }

    @Test fun membershipMutationWaitsForTheSameChatLockAsCommitAcceptance() = GlobalScope.promise {
        if (url == null) return@promise
        val id = "00000000-0000-0000-0000-000000000002"
        database {
            rawRows("INSERT INTO chats(id) VALUES(%V::uuid)", id)
            rawRows("INSERT INTO chat_members(chat_id,user_id) VALUES(%V::uuid,'lock-probe')", id)
        }
        coroutineScope {
            val acquired = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = async {
                database {
                    transaction {
                        rawRows("SELECT id FROM chats WHERE id = %V::uuid FOR UPDATE", id)
                        acquired.complete(Unit)
                        release.await()
                    }
                }
            }
            acquired.await()
            val mutation = async { database {
                rawRows("UPDATE chat_members SET left_at = now() WHERE chat_id = %V::uuid AND user_id = 'lock-probe'", id)
            } }
            try {
                withTimeout(5000) {
                    while (database { string("waiting", """
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                        WHERE query LIKE %V
                          AND cardinality(pg_blocking_pids(pid)) > 0)::text AS waiting
                    """.trimIndent(), "%UPDATE chat_members SET left_at = now()%lock-probe%") } != "true") delay(10)
                }
                assertFalse(mutation.isCompleted)
            } finally { release.complete(Unit) }
            holder.await()
            mutation.await()
        }
        assertEquals("1", database { string("count",
            "SELECT count(*)::text AS count FROM chat_members WHERE chat_id = %V::uuid AND left_at IS NOT NULL", id) })
    }

    @Test fun isolatedPostgresExercisesClaimsOrderingOwnershipAndRollback() = GlobalScope.promise {
        if (url == null) return@promise
        database {
            rawRows("INSERT INTO chats(id) VALUES(%V::uuid)", chat)
            members.forEach { rawRows("INSERT INTO chat_members(chat_id,user_id) VALUES(%V::uuid,%V)", chat, it) }
        }
        val alice = client("alice")
        val bob = client("bob")
        val lookup = DirectoryLookup(chat, members.toSet())
        val a = DeviceRegistration("alice-phone", ByteArray(32) { 1 }, 3, ByteArray(64) { 1 })
        val b = DeviceRegistration("bob-phone", ByteArray(32) { 2 }, 3, ByteArray(64) { 2 })
        alice.register(a)
        bob.register(b)
        assertTrue(runCatching { bob.register(a) }.isFailure)
        assertTrue(runCatching { alice.register(a.copy(signatureKey = ByteArray(32) { 3 })) }.isFailure)
        assertEquals(2, alice.devices(lookup).size)
        assertTrue(client("alice", "other-tenant").devices(lookup).isEmpty())
        assertTrue(runCatching { client("outsider").devices(lookup) }.isFailure)
        assertTrue(runCatching { alice.devices(lookup.copy(userIds = setOf("outsider"))) }.isFailure)
        for ((api, id) in listOf(alice to a.deviceId, bob to b.deviceId)) {
            val packages = (1..3).map { PublishedKeyPackage("$id-$it", byteArrayOf(it.toByte()), it == 3) }
            assertEquals(3, api.publishPackages(KeyPackagePublication(id, packages)))
        }
        val claims = coroutineScope { listOf(async { alice.claimPackages(lookup) }, async { bob.claimPackages(lookup) }).map { it.await() } }
        assertEquals(4, claims.flatten().map { it.reference }.toSet().size)
        assertTrue(claims.flatten().none { it.lastResort })
        val spare = alice.claimPackages(lookup)
        assertTrue(spare.all { it.lastResort })
        assertEquals(spare.map { it.reference }, bob.claimPackages(lookup).map { it.reference })
        val ids = setOf(a.deviceId, b.deviceId)
        fun proposal(sender: String, value: Byte) = CommitPublication(
            chat, sender, null, 1, byteArrayOf(value), byteArrayOf(value), ids, emptySet(),
            listOf(DirectoryWelcome("welcome-$sender", (ids - sender).single(), chat, 1, byteArrayOf(value))),
        )
        val pa = proposal(a.deviceId, 1)
        val pb = proposal(b.deviceId, 2)
        val raced = coroutineScope { listOf(async { alice.publishCommit(pa) }, async { bob.publishCommit(pb) }).map { it.await() } }
        assertEquals(1, raced.count { it.accepted })
        assertTrue(raced.all { it.epoch == 1L })
        val winner = if (raced[0].accepted) pa else pb
        val winningApi = if (raced[0].accepted) alice else bob
        assertTrue(winningApi.publishCommit(winner).accepted)
        assertEquals(1, notices.size)
        val history = alice.commits(CommitCursor(chat))
        assertEquals(1, history.size)
        assertEquals(members, history.single().members)
        assertEquals(ids, history.single().roster)
        assertEquals(members, bob.groupInfo(chat).members)
        assertEquals(ids, bob.groupInfo(chat).roster)
        assertEquals(1L, bob.groupInfo(chat).epoch)
        assertContentEquals(winner.groupInfo, bob.groupInfo(chat).bytes)
        val target = winner.welcomes.single().deviceId
        val targetApi = if (target == a.deviceId) alice else bob
        assertEquals(1, targetApi.welcomes(target).size)
        assertEquals(members, targetApi.welcomes(target).single().members)
        assertTrue(runCatching { winningApi.welcomes(target) }.isFailure)
        val tablet = DeviceRegistration("bob-tablet", ByteArray(32) { 3 }, 3, ByteArray(64) { 3 })
        bob.register(tablet)
        val failed = winner.copy(previousEpoch = 1, epoch = 2, added = setOf(tablet.deviceId), removed = emptySet(),
            welcomes = winner.welcomes.map { it.copy(deviceId = tablet.deviceId, epoch = 2) })
        assertTrue(runCatching { winningApi.publishCommit(failed) }.isFailure)
        assertEquals(1L, alice.groupInfo(chat).epoch)
        assertEquals(1, bob.commits(CommitCursor(chat)).size)
        targetApi.consumeWelcomes(WelcomeConsumption(target, setOf(winner.welcomes.single().id)))
        assertTrue(targetApi.welcomes(target).isEmpty())
        alice.revoke(a.deviceId)
        assertEquals(setOf(b.deviceId, tablet.deviceId), bob.devices(lookup).map { it.deviceId }.toSet())
        assertTrue(runCatching { alice.publishPackages(KeyPackagePublication(a.deviceId, listOf(PublishedKeyPackage("revoked", byteArrayOf(1))))) }.isFailure)
        assertTrue(runCatching { alice.register(a) }.isFailure)
        assertTrue(notices.isNotEmpty())
    }
}
