package dev.shibasis.reaktor.db.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.warn
import dev.shibasis.reaktor.db.ObjectChange
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.RawObject
import dev.shibasis.reaktor.db.StoredObject
import dev.shibasis.reaktor.db.UnreadableObjectException
import dev.shibasis.reaktor.io.serialization.TextSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import javax.sql.DataSource
import kotlin.reflect.KClass

class PostgresObjectDatabase(
    private val driver: SqlDriver,
    name: String,
    private val timestampProvider: TimestampProvider = DefaultTimestampProvider(),
    private val journalStorePrefix: String? = null,
) : ObjectDatabase(TextSerializer()) {
    constructor(dataSource: DataSource, name: String, journalStorePrefix: String? = null) :
        this(dataSource.asJdbcDriver(), name, DefaultTimestampProvider(), journalStorePrefix)

    private val table = "object_db_${name.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
    private val text = objectSerializer as TextSerializer
    private val log = "PostgresObjectDatabase".logger()
    private val initialization = Mutex()
    private var initialized = false

    init { require(journalStorePrefix == null || journalStorePrefix.isNotBlank()) { "A journal prefix cannot be blank" } }

    suspend fun ready() = initialization.withLock {
        if (!initialized) {
            driver.execute(null, schema(), 0).await()
            initialized = true
        }
    }

    private fun schema(): String {
        val columns = "key TEXT NOT NULL, value TEXT NOT NULL, store_name TEXT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY (store_name, key)"
        val journal = journalStorePrefix?.let { prefix ->
            val literal = "'${prefix.replace("'", "''")}'"
            """
            CREATE TABLE IF NOT EXISTS ${table}_journal_config (id INTEGER PRIMARY KEY CHECK (id = 1), prefix TEXT NOT NULL);
            INSERT INTO ${table}_journal_config (id, prefix) VALUES (1, $literal) ON CONFLICT (id) DO NOTHING;
            IF (SELECT prefix FROM ${table}_journal_config WHERE id = 1) <> $literal THEN
                RAISE EXCEPTION 'Journal prefix changes require an explicit storage migration';
            END IF;
            CREATE TABLE IF NOT EXISTS ${table}_changes (sequence BIGSERIAL PRIMARY KEY, store_name TEXT NOT NULL, key TEXT NOT NULL, payload TEXT, at_millis BIGINT NOT NULL);
            CREATE INDEX IF NOT EXISTS ${table}_changes_scope ON ${table}_changes (store_name, sequence);
            CREATE OR REPLACE FUNCTION ${table}_journal() RETURNS trigger LANGUAGE plpgsql AS ${'$'}journal${'$'}
            BEGIN
                IF TG_OP = 'DELETE' THEN
                    IF starts_with(OLD.store_name, $literal) THEN
                        PERFORM pg_advisory_xact_lock(hashtext('${table}_changes'));
                        INSERT INTO ${table}_changes (store_name, key, payload, at_millis)
                        VALUES (OLD.store_name, OLD.key, NULL, (extract(epoch FROM clock_timestamp()) * 1000)::BIGINT);
                    END IF;
                ELSIF starts_with(NEW.store_name, $literal) THEN
                    PERFORM pg_advisory_xact_lock(hashtext('${table}_changes'));
                    INSERT INTO ${table}_changes (store_name, key, payload, at_millis)
                    VALUES (NEW.store_name, NEW.key, NEW.value, NEW.updated_at);
                END IF;
                RETURN NULL;
            END
            ${'$'}journal${'$'};
            CREATE OR REPLACE TRIGGER ${table}_journal AFTER INSERT OR UPDATE OR DELETE ON $table FOR EACH ROW EXECUTE FUNCTION ${table}_journal();
            """.trimIndent()
        }.orEmpty()
        return """
            DO ${'$'}schema${'$'}
            BEGIN
                PERFORM pg_advisory_xact_lock(hashtext('$table'));
                CREATE TABLE IF NOT EXISTS $table ($columns);
                $journal
            END
            ${'$'}schema${'$'};
        """.trimIndent()
    }

    override suspend fun readChanges(storeName: String, afterSequence: Long, limit: Int): List<ObjectChange> {
        ready()
        require(journalStorePrefix != null && storeName.startsWith(journalStorePrefix)) { "Store was not opted into retained changes" }
        require(afterSequence >= 0 && limit in 1..256)
        return driver.executeQuery(null,
            "SELECT sequence, store_name, key, payload, at_millis FROM ${table}_changes WHERE store_name = ? AND sequence > ? ORDER BY sequence LIMIT ?",
            { cursor ->
                val changes = mutableListOf<ObjectChange>()
                while (cursor.next().value) changes += ObjectChange(cursor.long(0), cursor.string(1), cursor.string(2), cursor.getString(3), cursor.long(4))
                QueryResult.Value(changes)
            }, 3) {
            bindString(0, storeName)
            bindLong(1, afterSequence)
            bindLong(2, limit.toLong())
        }.await()
    }

    override suspend fun pruneChanges(storeName: String, throughSequence: Long, olderThanMillis: Long) {
        ready()
        require(journalStorePrefix != null && storeName.startsWith(journalStorePrefix)) { "Store was not opted into retained changes" }
        require(throughSequence >= 0)
        driver.execute(null, """
            DELETE FROM ${table}_changes WHERE store_name = ? AND sequence <= ? AND sequence < COALESCE(
                (SELECT sequence FROM ${table}_changes WHERE store_name = ? AND at_millis >= ? ORDER BY sequence LIMIT 1), ?)
        """.trimIndent(), 5) {
            bindString(0, storeName)
            bindLong(1, throughSequence)
            bindString(2, storeName)
            bindLong(3, olderThanMillis)
            bindLong(4, throughSequence + 1)
        }.await()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> putRaw(storeName: String, key: String, value: T, serializer: KSerializer<T>): StoredObject<T> {
        ready()
        upsert(storeName, key, text.serialize(serializer, value), timestampProvider.getTimestamp())
        return getRaw(storeName, key, value::class as KClass<T>, serializer) ?: error("Object disappeared immediately after put: $storeName/$key")
    }

    override suspend fun <T : Any> compareAndSetRaw(storeName: String, key: String, expected: T?, value: T, serializer: KSerializer<T>): Boolean {
        ready()
        val encoded = text.serialize(serializer, value)
        val now = timestampProvider.getTimestamp()
        val affected = if (expected == null) {
            driver.execute(null,
                "INSERT INTO $table (key, value, store_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (store_name, key) DO NOTHING", 5) {
                row(key, encoded, storeName, now, now)
            }.await()
        } else {
            driver.execute(null, "UPDATE $table SET value = ?, updated_at = ? WHERE store_name = ? AND key = ? AND value = ?", 5) {
                bindString(0, encoded)
                bindLong(1, now)
                bindString(2, storeName)
                bindString(3, key)
                bindString(4, text.serialize(serializer, expected))
            }.await()
        }
        return affected > 0
    }

    override suspend fun <T : Any> getRaw(storeName: String, key: String, type: KClass<T>, serializer: KSerializer<T>): StoredObject<T>? {
        ready()
        return driver.executeQuery(null, "SELECT $Columns FROM $table WHERE store_name = ? AND key = ?",
            { cursor -> QueryResult.Value(if (cursor.next().value) decode(cursor, serializer) else null) }, 2) {
            bindString(0, storeName)
            bindString(1, key)
        }.await()
    }

    override suspend fun <T : Any> getAllRaw(storeName: String, type: KClass<T>, serializer: KSerializer<T>): List<StoredObject<T>> {
        ready()
        return driver.executeQuery(null, "SELECT $Columns FROM $table WHERE store_name = ?", { cursor ->
            val items = mutableListOf<StoredObject<T>>()
            while (cursor.next().value) {
                try {
                    items += decode(cursor, serializer)
                } catch (unreadable: UnreadableObjectException) {
                    log.warn { "Skipping unreadable row in $table: ${unreadable.message}" }
                }
            }
            QueryResult.Value(items)
        }, 1) { bindString(0, storeName) }.await()
    }

    override suspend fun exportRaw(storeName: String?): List<RawObject> {
        ready()
        return driver.executeQuery(null, "SELECT $Columns FROM $table" + if (storeName != null) " WHERE store_name = ?" else "", { cursor ->
            val items = mutableListOf<RawObject>()
            while (cursor.next().value) items += RawObject(key = cursor.string(0), payload = cursor.string(1), storeName = cursor.string(2),
                createdAt = cursor.long(3), updatedAt = cursor.long(4))
            QueryResult.Value(items)
        }, if (storeName != null) 1 else 0) { if (storeName != null) bindString(0, storeName) }.await()
    }

    override suspend fun importRawInternal(items: List<RawObject>) {
        ready()
        items.forEach { item -> upsert(item.storeName, item.key, item.payload, item.updatedAt, item.createdAt) }
    }

    override suspend fun renameRaw(storeName: String, key: String, newKey: String): Boolean {
        ready()
        return driver.execute(null,
            "UPDATE $table SET key = ? WHERE store_name = ? AND key = ? AND NOT EXISTS (SELECT 1 FROM $table WHERE store_name = ? AND key = ?)", 5) {
            bindString(0, newKey)
            bindString(1, storeName)
            bindString(2, key)
            bindString(3, storeName)
            bindString(4, newKey)
        }.await() > 0
    }

    override suspend fun deleteRaw(storeName: String, key: String) {
        ready()
        driver.execute(null, "DELETE FROM $table WHERE store_name = ? AND key = ?", 2) {
            bindString(0, storeName)
            bindString(1, key)
        }.await()
    }

    override suspend fun clearRaw(storeName: String) {
        ready()
        driver.execute(null, "DELETE FROM $table WHERE store_name = ?", 1) { bindString(0, storeName) }.await()
    }

    override suspend fun clearRaw() {
        ready()
        driver.execute(null, "DELETE FROM $table", 0).await()
    }

    private suspend fun upsert(storeName: String, key: String, payload: String, updatedAt: Long, createdAt: Long = updatedAt) {
        driver.execute(null, """
            INSERT INTO $table (key, value, store_name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (store_name, key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
        """.trimIndent(), 5) { row(key, payload, storeName, createdAt, updatedAt) }.await()
    }

    private fun SqlPreparedStatement.row(key: String, payload: String, storeName: String, createdAt: Long, updatedAt: Long) {
        bindString(0, key)
        bindString(1, payload)
        bindString(2, storeName)
        bindLong(3, createdAt)
        bindLong(4, updatedAt)
    }

    private fun <T : Any> decode(cursor: SqlCursor, serializer: KSerializer<T>): StoredObject<T> {
        val key = cursor.string(0)
        val payload = cursor.string(1)
        val storeName = cursor.string(2)
        val value = try {
            text.deserialize(serializer, payload)
        } catch (unreadable: SerializationException) {
            throw UnreadableObjectException(storeName, key, unreadable)
        }
        return StoredObject(key = key, value = value, storeName = storeName, createdAt = cursor.long(3), updatedAt = cursor.long(4),
            sizeBytes = payload.length.toLong() * 2)
    }
}

private const val Columns = "key, value, store_name, created_at, updated_at"

private fun SqlCursor.string(index: Int): String = checkNotNull(getString(index)) { "Column $index is null" }

private fun SqlCursor.long(index: Int): Long = checkNotNull(getLong(index)) { "Column $index is null" }
