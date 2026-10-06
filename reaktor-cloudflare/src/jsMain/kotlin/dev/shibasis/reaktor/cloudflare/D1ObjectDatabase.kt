package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.ObjectChange
import dev.shibasis.reaktor.db.core.ObjectJournalSchema
import dev.shibasis.reaktor.db.RawObject
import dev.shibasis.reaktor.db.StoredObject
import dev.shibasis.reaktor.db.UnreadableObjectException
import dev.shibasis.reaktor.io.serialization.TextSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.reflect.KClass
import kotlin.time.Clock

/** Native D1 authority for a Worker host; no browser worker or separate scheduler database. */
class D1ObjectDatabase(private val d1: D1Database, name: String, private val journalStorePrefix: String? = null) : ObjectDatabase(TextSerializer()) {
    private val table = "object_db_${name.replace(Regex("[^A-Za-z0-9_]"), "_")}"
    private val initialization = Mutex()
    private var ready = false
    private val codec get() = objectSerializer as TextSerializer

    private suspend fun ready() = initialization.withLock {
        if (!ready) {
            mutation("CREATE TABLE IF NOT EXISTS $table (store_name TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(store_name,key))")
            journalStorePrefix?.let { prefix ->
                val schema = ObjectJournalSchema(table, prefix)
                mutation(schema.createConfiguration)
                mutation(schema.setConfiguration, prefix)
                check(d1.rawFirstOrNull(SqlStatement(schema.getConfiguration, emptyArray()))?.requireString("prefix") == prefix) {
                    "Journal prefix changes require an explicit storage migration"
                }
                schema.statements.forEach { mutation(it) }
            }
            ready = true
        }
    }

    private suspend fun mutation(sql: String, vararg args: Any?): Int {
        val result = d1.execute(SqlStatement(sql, args.map { it }.toTypedArray()))
        check(result.success) { "D1 mutation failed" }
        return checkNotNull(result.changedRowCount) { "D1 mutation did not report affected rows" }
    }

    override suspend fun <T : Any> putRaw(storeName: String, key: String, value: T, serializer: KSerializer<T>): StoredObject<T> {
        ready()
        val timestamp = Clock.System.now().toEpochMilliseconds()
        mutation("INSERT INTO $table(store_name,key,value,created_at,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(store_name,key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
            storeName, key, codec.serialize(serializer, value), timestamp, timestamp)
        @Suppress("UNCHECKED_CAST")
        return checkNotNull(getRaw(storeName, key, value::class as KClass<T>, serializer))
    }

    override suspend fun <T : Any> compareAndSetRaw(storeName: String, key: String, expected: T?, value: T, serializer: KSerializer<T>): Boolean {
        ready()
        val encoded = codec.serialize(serializer, value)
        val timestamp = Clock.System.now().toEpochMilliseconds()
        return if (expected == null) mutation("INSERT OR IGNORE INTO $table(store_name,key,value,created_at,updated_at) VALUES(?,?,?,?,?)", storeName, key, encoded, timestamp, timestamp) > 0
        else mutation("UPDATE $table SET value=?,updated_at=? WHERE store_name=? AND key=? AND value=?", encoded, timestamp, storeName, key, codec.serialize(serializer, expected)) > 0
    }

    private fun <T : Any> decode(row: SqlRow, serializer: KSerializer<T>): StoredObject<T> {
        val store = row.requireString("store_name")
        val key = row.requireString("key")
        val payload = row.requireString("value")
        val value = try { codec.deserialize(serializer, payload) }
            catch (invalid: SerializationException) { throw UnreadableObjectException(store, key, invalid) }
        return StoredObject(key, value, store, row.requireString("created_at").toLong(), row.requireString("updated_at").toLong(), payload.encodeToByteArray().size.toLong())
    }

    override suspend fun <T : Any> getRaw(storeName: String, key: String, type: KClass<T>, serializer: KSerializer<T>): StoredObject<T>? {
        ready()
        return d1.rawFirstOrNull(SqlStatement("SELECT * FROM $table WHERE store_name=? AND key=?", arrayOf(storeName, key)))?.let { decode(it, serializer) }
    }

    override suspend fun <T : Any> getAllRaw(storeName: String, type: KClass<T>, serializer: KSerializer<T>): List<StoredObject<T>> {
        ready()
        return d1.rawRows(SqlStatement("SELECT * FROM $table WHERE store_name=?", arrayOf(storeName))).mapNotNull {
            try { decode(it, serializer) } catch (invalid: UnreadableObjectException) { null }
        }
    }

    override suspend fun deleteRaw(storeName: String, key: String) { ready(); mutation("DELETE FROM $table WHERE store_name=? AND key=?", storeName, key) }
    override suspend fun clearRaw(storeName: String) { ready(); mutation("DELETE FROM $table WHERE store_name=?", storeName) }
    override suspend fun clearRaw() { ready(); mutation("DELETE FROM $table") }

    override suspend fun exportRaw(storeName: String?): List<RawObject> {
        ready()
        val statement = if (storeName == null) SqlStatement("SELECT * FROM $table", emptyArray())
        else SqlStatement("SELECT * FROM $table WHERE store_name=?", arrayOf(storeName))
        return d1.rawRows(statement).map { RawObject(it.requireString("key"), it.requireString("store_name"), it.requireString("value"),
            it.requireString("created_at").toLong(), it.requireString("updated_at").toLong()) }
    }

    override suspend fun readChanges(storeName: String, afterSequence: Long, limit: Int): List<ObjectChange> {
        ready()
        require(journalStorePrefix != null && storeName.startsWith(journalStorePrefix)) { "Store was not opted into retained changes" }
        require(afterSequence >= 0 && limit in 1..256)
        return d1.rawRows(SqlStatement(ObjectJournalSchema(table, journalStorePrefix).read, arrayOf(storeName, afterSequence, limit))).map {
            ObjectChange(it.requireString("sequence").toLong(), it.requireString("store_name"), it.requireString("key"),
                it.string("payload"), it.requireString("at_millis").toLong())
        }
    }
}
