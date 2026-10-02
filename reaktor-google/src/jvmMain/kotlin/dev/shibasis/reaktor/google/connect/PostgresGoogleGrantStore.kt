package dev.shibasis.reaktor.google.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

class PostgresGoogleGrantStore(private val dataSource: DataSource) : GoogleGrantStore {
    private val preparing = Mutex()

    @Volatile
    private var prepared = false

    private suspend fun prepare() {
        if (prepared) return
        preparing.withLock {
            if (prepared) return
            transaction { connection -> connection.createStatement().use { statement -> SCHEMA.forEach(statement::execute) } }
            prepared = true
        }
    }

    override suspend fun read(key: GrantKey): String? = sql { connection ->
        connection.prepareStatement("SELECT sealed FROM $GRANTS WHERE service = ? AND subject = ?").use { statement ->
            statement.setString(1, key.service)
            statement.setString(2, key.subject)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    override suspend fun write(key: GrantKey, sealed: String) = sql { connection ->
        connection.prepareStatement(
            "INSERT INTO $GRANTS (service, subject, sealed) VALUES (?, ?, ?) " +
                "ON CONFLICT (service, subject) DO UPDATE SET sealed = EXCLUDED.sealed, updated_at = now()",
        ).use { statement ->
            statement.setString(1, key.service)
            statement.setString(2, key.subject)
            statement.setString(3, sealed)
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun delete(key: GrantKey): Boolean = sql { connection ->
        connection.prepareStatement("DELETE FROM $GRANTS WHERE service = ? AND subject = ?").use { statement ->
            statement.setString(1, key.service)
            statement.setString(2, key.subject)
            statement.executeUpdate() > 0
        }
    }

    override suspend fun holdConsent(consent: Held, staleBefore: Instant) = sql { connection ->
        connection.prepareStatement("DELETE FROM $CONSENTS WHERE created_at < ?").use { statement ->
            statement.setObject(1, staleBefore.utc())
            statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO $CONSENTS (state_hash, service, subject, sealed, created_at) VALUES (?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, consent.hash)
            statement.setString(2, consent.key.service)
            statement.setString(3, consent.key.subject)
            statement.setString(4, consent.sealed)
            statement.setObject(5, consent.createdAt.utc())
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun takeConsent(stateHash: String): Held? = sql { connection ->
        connection.prepareStatement("DELETE FROM $CONSENTS WHERE state_hash = ? RETURNING state_hash AS hash, service, subject, sealed, created_at").use { statement ->
            statement.setString(1, stateHash)
            statement.executeQuery().use { rows -> if (rows.next()) rows.held() else null }
        }
    }

    override suspend fun holdPending(pending: Held) = sql { connection ->
        connection.prepareStatement("INSERT INTO $PENDING (handle_hash, service, subject, sealed, created_at) VALUES (?, ?, ?, ?, ?)").use { statement ->
            statement.setString(1, pending.hash)
            statement.setString(2, pending.key.service)
            statement.setString(3, pending.key.subject)
            statement.setString(4, pending.sealed)
            statement.setObject(5, pending.createdAt.utc())
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun takePending(handleHash: String): Held? = sql { connection ->
        connection.prepareStatement("DELETE FROM $PENDING WHERE handle_hash = ? RETURNING handle_hash AS hash, service, subject, sealed, created_at").use { statement ->
            statement.setString(1, handleHash)
            statement.executeQuery().use { rows -> if (rows.next()) rows.held() else null }
        }
    }

    override suspend fun pendingBefore(cutoff: Instant): List<Held> = sql { connection ->
        connection.prepareStatement("SELECT handle_hash AS hash, service, subject, sealed, created_at FROM $PENDING WHERE created_at < ? ORDER BY created_at LIMIT $SWEEP_LIMIT").use { statement ->
            statement.setObject(1, cutoff.utc())
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.held()) } }
        }
    }

    private fun ResultSet.held(): Held = Held(
        hash = getString("hash"),
        key = GrantKey(getString("service"), getString("subject")),
        sealed = getString("sealed"),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )

    private suspend fun <T> sql(block: (Connection) -> T): T {
        prepare()
        return transaction(block)
    }

    private suspend fun <T> transaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun Instant.utc(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

    companion object {
        internal const val SCHEMA_NAME = "google_connect"
        private const val GRANTS = "$SCHEMA_NAME.grants"
        private const val CONSENTS = "$SCHEMA_NAME.consents"
        private const val PENDING = "$SCHEMA_NAME.pending_grants"
        private const val SWEEP_LIMIT = 50

        private val SCHEMA: List<String> = listOf(
            "CREATE SCHEMA IF NOT EXISTS $SCHEMA_NAME",
            "CREATE TABLE IF NOT EXISTS $GRANTS (service text NOT NULL, subject text NOT NULL, sealed text NOT NULL, " +
                "created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (service, subject))",
            "CREATE TABLE IF NOT EXISTS $CONSENTS (state_hash text PRIMARY KEY, service text NOT NULL, subject text NOT NULL, " +
                "sealed text NOT NULL, created_at timestamptz NOT NULL)",
            "CREATE INDEX IF NOT EXISTS consents_created_at ON $CONSENTS (created_at)",
            "CREATE TABLE IF NOT EXISTS $PENDING (handle_hash text PRIMARY KEY, service text NOT NULL, subject text NOT NULL, " +
                "sealed text NOT NULL, created_at timestamptz NOT NULL)",
            "CREATE INDEX IF NOT EXISTS pending_grants_created_at ON $PENDING (created_at)",
            "ALTER TABLE $GRANTS ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE $CONSENTS ENABLE ROW LEVEL SECURITY",
            "ALTER TABLE $PENDING ENABLE ROW LEVEL SECURITY",
            "REVOKE ALL ON SCHEMA $SCHEMA_NAME FROM PUBLIC",
            "REVOKE ALL ON ALL TABLES IN SCHEMA $SCHEMA_NAME FROM PUBLIC",
            """
            DO ${'$'}${'$'}
            DECLARE grantee_name text;
            BEGIN
                FOR grantee_name IN
                    SELECT r.rolname FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    CROSS JOIN LATERAL aclexplode(c.relacl) a
                    JOIN pg_roles r ON r.oid = a.grantee
                    WHERE n.nspname = '$SCHEMA_NAME' AND a.grantee <> c.relowner
                    UNION
                    SELECT r.rolname FROM pg_namespace n
                    CROSS JOIN LATERAL aclexplode(n.nspacl) a
                    JOIN pg_roles r ON r.oid = a.grantee
                    WHERE n.nspname = '$SCHEMA_NAME' AND a.grantee <> n.nspowner
                LOOP
                    EXECUTE format('REVOKE ALL ON SCHEMA $SCHEMA_NAME FROM %I', grantee_name);
                    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA $SCHEMA_NAME FROM %I', grantee_name);
                END LOOP;
            END
            ${'$'}${'$'}
            """.trimIndent(),
        )
    }
}
