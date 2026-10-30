package dev.shibasis.reaktor.security

import dev.shibasis.reaktor.cloudflare.PostgresDatabase
import dev.shibasis.reaktor.cloudflare.SqlRow
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.contract.reject
import kotlinx.serialization.encodeToString
import kotlin.io.encoding.Base64

class DirectorySession(
    val database: PostgresDatabase,
    val tenantId: String,
    val appId: String,
    val userId: String,
    private val authorityProvider: (PostgresDatabase) -> ConversationAuthority,
) {
    val authority = authorityProvider(database)
    fun withDatabase(database: PostgresDatabase) = DirectorySession(database, tenantId, appId, userId, authorityProvider)
}

abstract class PostgresSecureDirectory : SecureDirectoryApi() {
    protected abstract suspend fun <T> authenticated(block: suspend DirectorySession.() -> T): T
    protected abstract suspend fun enroll(registration: DeviceRegistration): ByteArray
    protected abstract suspend fun announce(commit: DirectoryCommit, welcomes: List<DirectoryWelcome>)
    protected open suspend fun authorizeActivation(session: DirectorySession, publication: CommitPublication) = Unit

    override val register = super.register.handle { registration ->
        authenticated {
            identifier(registration.deviceId)
            valid(registration.cipherSuite in 1..3)
            valid(registration.signatureKey.size == if (registration.cipherSuite == 2) 65 else 32)
            valid(registration.proof.size in 1..144)
            val credential = enroll(registration)
            valid(credential.size in 1..32768)
            val row = database.rawFirstOrNull(
                """
                INSERT INTO security_devices (tenant_id, app_id, device_id, user_id, credential, signature_key, capabilities)
                VALUES (%V, %V, %V, %V, decode(%V, 'base64'), decode(%V, 'base64'), %V)
                ON CONFLICT (tenant_id, app_id, device_id) DO UPDATE
                SET credential = EXCLUDED.credential, last_seen_at = now()
                WHERE security_devices.user_id = EXCLUDED.user_id
                  AND security_devices.signature_key = EXCLUDED.signature_key
                  AND security_devices.capabilities = EXCLUDED.capabilities
                  AND security_devices.revoked_at IS NULL
                RETURNING device_id
                """.trimIndent(),
                tenantId, appId, registration.deviceId, userId, Base64.encode(credential),
                Base64.encode(registration.signatureKey), registration.cipherSuite,
            )
            if (row == null) reject(409, "Device identity already exists")
            DirectoryDevice(registration.deviceId, userId, credential, registration.signatureKey, registration.cipherSuite)
        }
    }

    override val publishPackages = super.publishPackages.handle { publication ->
        authenticated {
            valid(publication.packages.size in 0..256)
            valid(publication.packages.map { it.reference }.toSet().size == publication.packages.size)
            valid(publication.packages.count { it.lastResort } <= 1)
            publication.packages.forEach { identifier(it.reference); valid(it.bytes.size in 1..1048576) }
            database.transaction {
                ownDevice(this, publication.deviceId)
                val count = string("count", """
                    SELECT count(*)::text AS count FROM security_key_packages
                    WHERE tenant_id = %V AND app_id = %V AND device_id = %V AND claimed_at IS NULL
                """.trimIndent(), tenantId, appId, publication.deviceId)?.toInt() ?: 0
                valid(count + publication.packages.size <= 4096)
                publication.packages.forEach { keyPackage ->
                    if (keyPackage.lastResort) rawRows("""
                        DELETE FROM security_key_packages WHERE tenant_id = %V AND app_id = %V
                        AND device_id = %V AND last_resort AND ref <> %V
                    """.trimIndent(), tenantId, appId, publication.deviceId, keyPackage.reference)
                    val inserted = rawFirstOrNull("""
                        INSERT INTO security_key_packages (tenant_id, app_id, device_id, ref, key_package, last_resort)
                        VALUES (%V, %V, %V, %V, decode(%V, 'base64'), %V)
                        ON CONFLICT (tenant_id, app_id, ref) DO UPDATE SET ref = EXCLUDED.ref
                        WHERE security_key_packages.device_id = EXCLUDED.device_id
                          AND security_key_packages.key_package = EXCLUDED.key_package
                          AND security_key_packages.last_resort = EXCLUDED.last_resort
                        RETURNING ref
                    """.trimIndent(), tenantId, appId, publication.deviceId, keyPackage.reference,
                        Base64.encode(keyPackage.bytes), keyPackage.lastResort)
                    if (inserted == null) reject(409, "Key package reference already exists")
                }
                string("count", """
                    SELECT count(*)::text AS count FROM security_key_packages
                    WHERE tenant_id = %V AND app_id = %V AND device_id = %V AND claimed_at IS NULL
                """.trimIndent(), tenantId, appId, publication.deviceId)?.toInt() ?: 0
            }
        }
    }

    override val devices = super.devices.handle { lookup -> authenticated { lookupDevices(lookup) } }

    override val claimPackages = super.claimPackages.handle { lookup ->
        authenticated {
            val found = lookupDevices(lookup)
            database.transaction {
                found.map { device ->
                    val row = rawFirstOrNull("""
                        SELECT ref, encode(key_package, 'base64') AS bytes, last_resort
                        FROM security_key_packages
                        WHERE tenant_id = %V AND app_id = %V AND device_id = %V
                          AND (claimed_at IS NULL OR last_resort)
                        ORDER BY last_resort, created_at, ref
                        LIMIT 1 FOR UPDATE SKIP LOCKED
                    """.trimIndent(), tenantId, appId, device.deviceId)
                        ?: reject(409, "A member device needs key packages")
                    val lastResort = row.requireBoolean("last_resort")
                    if (!lastResort) rawRows("""
                        UPDATE security_key_packages SET claimed_at = now(), claimed_for = %V
                        WHERE tenant_id = %V AND app_id = %V AND ref = %V
                    """.trimIndent(), lookup.conversationId, tenantId, appId, row.requireString("ref"))
                    DirectoryKeyPackage(device, row.requireString("ref"), bytes(row, "bytes"), lastResort)
                }
            }
        }
    }

    override val revoke = super.revoke.handle { deviceId ->
        authenticated {
            identifier(deviceId)
            database.transaction {
                ownDevice(this, deviceId)
                rawRows("""
                    UPDATE security_devices SET revoked_at = now(), revoke_reason = 'user'
                    WHERE tenant_id = %V AND app_id = %V AND device_id = %V AND user_id = %V
                """.trimIndent(), tenantId, appId, deviceId, userId)
                rawRows("""
                    DELETE FROM security_key_packages WHERE tenant_id = %V AND app_id = %V AND device_id = %V
                """.trimIndent(), tenantId, appId, deviceId)
            }
            Unit
        }
    }

    override val publishCommit = super.publishCommit.handle { publication ->
        authenticated {
            identifier(publication.conversationId)
            identifier(publication.senderDeviceId)
            valid(publication.epoch > 0 && publication.epoch < Long.MAX_VALUE)
            valid(publication.previousEpoch?.let { it >= 0 && publication.epoch == it + 1 } ?: (publication.epoch == 1L))
            valid(publication.commit.isNotEmpty() && publication.groupInfo.isNotEmpty())
            valid(publication.added.size <= 4096 && publication.removed.size <= 4096)
            valid(publication.added.intersect(publication.removed).isEmpty())
            (publication.added + publication.removed).forEach(::identifier)
            valid(publication.welcomes.size <= 4096)
            valid(publication.welcomes.map { it.id }.toSet().size == publication.welcomes.size)
            valid(publication.welcomes.map { it.deviceId }.toSet().size == publication.welcomes.size)
            valid(publication.commit.size.toLong() + publication.groupInfo.size + publication.welcomes.sumOf { it.bytes.size.toLong() } <= 16777216)
            publication.welcomes.forEach {
                identifier(it.id)
                valid(it.deviceId in publication.added && it.conversationId == publication.conversationId && it.epoch == publication.epoch && it.bytes.isNotEmpty())
            }
            var inserted = false
            var epochMembers = emptySet<String>()
            var nextRoster = emptySet<String>()
            val acceptance = database.transaction {
                if (rawFirstOrNull("SELECT id FROM chats WHERE id::text = %V AND deleted_at IS NULL FOR UPDATE",
                        publication.conversationId) == null) reject(404, "Conversation not found")
                val session = this@authenticated.withDatabase(this)
                session.member(publication.conversationId)
                ownDevice(this, publication.senderDeviceId)
                val existing = rawFirstOrNull("""
                    SELECT sender_device_id, encode(commit, 'base64') AS bytes FROM security_commits
                    WHERE tenant_id = %V AND app_id = %V AND chat_id = %V AND epoch = %V
                """.trimIndent(), tenantId, appId, publication.conversationId, publication.epoch)
                if (existing != null && existing.requireString("sender_device_id") == publication.senderDeviceId &&
                    bytes(existing, "bytes").contentEquals(publication.commit)) return@transaction CommitAcceptance(true, publication.epoch)
                val previous = rawFirstOrNull("""
                    SELECT epoch::text AS epoch, roster::text AS roster FROM security_commits
                    WHERE tenant_id = %V AND app_id = %V AND chat_id = %V ORDER BY epoch DESC LIMIT 1
                """.trimIndent(), tenantId, appId, publication.conversationId)
                if (previous?.requireString("epoch")?.toLong() != publication.previousEpoch)
                    return@transaction CommitAcceptance(false, previous?.requireString("epoch")?.toLong())
                if (publication.previousEpoch == null) authorizeActivation(session, publication)
                val active = session.lookupDevices(DirectoryLookup(publication.conversationId,
                    session.authority.members(publication.conversationId))).map { it.deviceId }.toSet()
                valid(publication.senderDeviceId in active && active.containsAll(publication.added))
                valid((publication.added - publication.senderDeviceId).all { added -> publication.welcomes.any { it.deviceId == added } })
                val oldRoster = previous?.let { json.decodeFromString<Set<String>>(it.requireString("roster")) }.orEmpty()
                valid(oldRoster.containsAll(publication.removed) && publication.added.intersect(oldRoster).isEmpty())
                nextRoster = oldRoster - publication.removed + publication.added
                valid(nextRoster.size in 1..4096 && publication.senderDeviceId in nextRoster)
                if (publication.previousEpoch == null) valid(nextRoster == active)
                val rosterDevices = rawRows("""
                    SELECT device_id, user_id FROM security_devices WHERE tenant_id = %V AND app_id = %V
                    AND device_id IN (SELECT jsonb_array_elements_text(%V::text::jsonb))
                """.trimIndent(), tenantId, appId, json.encodeToString(nextRoster.sorted()))
                valid(rosterDevices.size == nextRoster.size)
                epochMembers = rosterDevices.map { it.requireString("user_id") }.toSet()
                val updated = rawFirstOrNull("""
                    UPDATE chats SET e2ee_epoch = %V, e2ee_group_info = decode(%V, 'base64')
                    WHERE id::text = %V AND deleted_at IS NULL AND e2ee_epoch IS NOT DISTINCT FROM %V::bigint
                    RETURNING e2ee_epoch::text AS epoch
                """.trimIndent(), publication.epoch.toString(), Base64.encode(publication.groupInfo), publication.conversationId,
                    publication.previousEpoch?.toString())
                if (updated == null) return@transaction CommitAcceptance(false, currentEpoch(this, publication.conversationId))
                rawRows("""
                    INSERT INTO security_commits (tenant_id, app_id, chat_id, epoch, commit, sender_device_id, added, removed, member_users, roster)
                    VALUES (%V, %V, %V, %V, decode(%V, 'base64'), %V, %V::text::jsonb, %V::text::jsonb, %V::text::jsonb, %V::text::jsonb)
                """.trimIndent(), tenantId, appId, publication.conversationId, publication.epoch.toString(),
                    Base64.encode(publication.commit), publication.senderDeviceId,
                    json.encodeToString(publication.added.sorted()), json.encodeToString(publication.removed.sorted()),
                    json.encodeToString(epochMembers.sorted()), json.encodeToString(nextRoster.sorted()))
                publication.welcomes.forEach { welcome ->
                    rawRows("""
                        INSERT INTO security_welcomes (tenant_id, app_id, id, device_id, chat_id, epoch, welcome)
                        VALUES (%V, %V, %V, %V, %V, %V, decode(%V, 'base64'))
                    """.trimIndent(), tenantId, appId, welcome.id, welcome.deviceId, welcome.conversationId,
                        welcome.epoch.toString(), Base64.encode(welcome.bytes))
                }
                inserted = true
                CommitAcceptance(true, publication.epoch)
            }
            if (inserted) announce(DirectoryCommit(publication.conversationId, publication.epoch,
                publication.commit, publication.senderDeviceId, publication.added, publication.removed, epochMembers, nextRoster),
                publication.welcomes.map { it.copy(members = epochMembers) })
            acceptance
        }
    }

    override val commits = super.commits.handle { cursor ->
        authenticated {
            member(cursor.conversationId)
            valid(cursor.afterEpoch >= -1)
            database.rawRows("""
                SELECT epoch::text AS epoch, encode(commit, 'base64') AS bytes, sender_device_id,
                       added::text AS added, removed::text AS removed, member_users::text AS member_users, roster::text AS roster
                FROM security_commits WHERE tenant_id = %V AND app_id = %V AND chat_id = %V AND epoch > %V
                ORDER BY epoch LIMIT 512
            """.trimIndent(), tenantId, appId, cursor.conversationId, cursor.afterEpoch.toString()).map {
                DirectoryCommit(cursor.conversationId, it.requireString("epoch").toLong(), bytes(it, "bytes"),
                    it.requireString("sender_device_id"), json.decodeFromString<Set<String>>(it.requireString("added")),
                    json.decodeFromString<Set<String>>(it.requireString("removed")),
                    json.decodeFromString<Set<String>>(it.requireString("member_users")),
                    json.decodeFromString<Set<String>>(it.requireString("roster")))
            }
        }
    }

    override val groupInfo = super.groupInfo.handle { conversationId ->
        authenticated {
            member(conversationId)
            val row = database.rawFirstOrNull("""
                SELECT c.e2ee_epoch::text AS epoch, encode(c.e2ee_group_info, 'base64') AS bytes,
                       s.member_users::text AS member_users, s.roster::text AS roster
                FROM chats c LEFT JOIN security_commits s ON s.chat_id = c.id AND s.epoch = c.e2ee_epoch
                  AND s.tenant_id = %V AND s.app_id = %V
                WHERE c.id::text = %V AND c.deleted_at IS NULL
            """.trimIndent(), tenantId, appId, conversationId) ?: reject(404, "Conversation not found")
            DirectoryGroupInfo(row.string("epoch")?.toLong(), row.string("bytes")?.let(Base64.Mime::decode),
                row.string("member_users")?.let { json.decodeFromString<Set<String>>(it) }.orEmpty(),
                row.string("roster")?.let { json.decodeFromString<Set<String>>(it) }.orEmpty())
        }
    }

    override val welcomes = super.welcomes.handle { deviceId ->
        authenticated {
            ownDevice(database, deviceId)
            database.rawRows("""
                SELECT w.id, w.chat_id, w.epoch::text AS epoch, encode(w.welcome, 'base64') AS bytes,
                       s.member_users::text AS member_users
                FROM security_welcomes w JOIN security_commits s
                  ON s.tenant_id = w.tenant_id AND s.app_id = w.app_id AND s.chat_id = w.chat_id AND s.epoch = w.epoch
                WHERE w.tenant_id = %V AND w.app_id = %V AND w.device_id = %V AND w.consumed_at IS NULL
                ORDER BY w.created_at, w.id LIMIT 512
            """.trimIndent(), tenantId, appId, deviceId).map {
                DirectoryWelcome(it.requireString("id"), deviceId, it.requireString("chat_id"), it.requireString("epoch").toLong(), bytes(it, "bytes"),
                    json.decodeFromString<Set<String>>(it.requireString("member_users")))
            }
        }
    }

    override val consumeWelcomes = super.consumeWelcomes.handle { consumption ->
        authenticated {
            valid(consumption.ids.size in 1..512)
            consumption.ids.forEach(::identifier)
            database.transaction {
                ownDevice(this, consumption.deviceId)
                rawRows("""
                    UPDATE security_welcomes SET consumed_at = now()
                    WHERE tenant_id = %V AND app_id = %V AND device_id = %V
                    AND id IN (SELECT jsonb_array_elements_text(%V::text::jsonb))
                """.trimIndent(), tenantId, appId, consumption.deviceId, json.encodeToString(consumption.ids.sorted()))
            }
            Unit
        }
    }

    private suspend fun DirectorySession.lookupDevices(lookup: DirectoryLookup): List<DirectoryDevice> {
        member(lookup.conversationId)
        valid(lookup.userIds.size <= 4096)
        lookup.userIds.forEach(::identifier)
        val members = authority.members(lookup.conversationId)
        if (!members.containsAll(lookup.userIds)) reject(403, "Users must belong to the conversation")
        val users = lookup.userIds.ifEmpty { members }
        valid(users.size in 1..4096)
        val rows = database.rawRows("""
            SELECT device_id, user_id, encode(credential, 'base64') AS credential,
                   encode(signature_key, 'base64') AS signature_key, capabilities
            FROM security_devices WHERE tenant_id = %V AND app_id = %V AND revoked_at IS NULL
              AND user_id IN (SELECT jsonb_array_elements_text(%V::text::jsonb))
            ORDER BY user_id, device_id LIMIT 4097
        """.trimIndent(), tenantId, appId, json.encodeToString(users.sorted()))
        valid(rows.size <= 4096)
        return rows.map { DirectoryDevice(it.requireString("device_id"), it.requireString("user_id"),
            bytes(it, "credential"), bytes(it, "signature_key"), it.requireInt("capabilities")) }
    }

    private suspend fun DirectorySession.member(conversationId: String) {
        identifier(conversationId)
        if (!authority.isMember(conversationId, userId)) reject(403, "Conversation membership required")
    }

    private suspend fun DirectorySession.ownDevice(db: PostgresDatabase, deviceId: String) {
        identifier(deviceId)
        if (db.rawFirstOrNull("""
            SELECT device_id FROM security_devices WHERE tenant_id = %V AND app_id = %V
            AND device_id = %V AND user_id = %V AND revoked_at IS NULL FOR UPDATE
        """.trimIndent(), tenantId, appId, deviceId, userId) == null) reject(403, "Active device ownership required")
    }

    private suspend fun currentEpoch(database: PostgresDatabase, conversationId: String): Long? =
        database.string("epoch", "SELECT e2ee_epoch::text AS epoch FROM chats WHERE id::text = %V", conversationId)?.toLong()

    private fun bytes(row: SqlRow, column: String): ByteArray = Base64.Mime.decode(row.requireString(column))
    private fun identifier(value: String) = valid(value.isNotEmpty() && value.length <= 4096 && value.all { it.code in 0x21..0x7e })
    private fun valid(condition: Boolean) { if (!condition) reject(400, "Invalid security directory request") }
}
