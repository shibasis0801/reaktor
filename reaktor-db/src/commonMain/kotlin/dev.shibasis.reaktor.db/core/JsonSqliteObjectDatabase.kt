package dev.shibasis.reaktor.db.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.shibasis.reaktor.io.serialization.BinarySerializer
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import dev.shibasis.reaktor.io.serialization.TextSerializer
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.StoredObject
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

class SqliteObjectDatabase(
    private val driver: SqlDriver,
    name: String,
    objectSerializer: ObjectSerializer<*> = TextSerializer(),
    private val timestampProvider: TimestampProvider = DefaultTimestampProvider()
): ObjectDatabase(objectSerializer) {
    private val tableName = "object_db_${name.replace(Regex("[^A-Za-z0-9_]"), "_")}"

    init {
        val valueType = objectSerializer.choose("TEXT", "BLOB")
        driver.execute(null, """
            CREATE TABLE IF NOT EXISTS $tableName (
                key TEXT NOT NULL,
                value $valueType NOT NULL,
                store_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (store_name, key)
            )
        """.trimIndent(), 0)
    }

    private fun <T : Any> readRow(cursor: SqlCursor, serializer: KSerializer<T>): StoredObject<T>? {
        if (!cursor.next().value) return null
        val (value, sizeBytes) = when (objectSerializer) {
            is BinarySerializer -> {
                val bytes = cursor.getBytes(1)!!
                objectSerializer.deserialize(serializer, bytes) to bytes.size.toLong()
            }
            is TextSerializer -> {
                val text = cursor.getString(1)!!
                objectSerializer.deserialize(serializer, text) to (text.length.toLong() * 2)
            }
        }
        return StoredObject(
            key = cursor.getString(0)!!,
            value = value,
            storeName = cursor.getString(2)!!,
            createdAt = cursor.getLong(3)!!,
            updatedAt = cursor.getLong(4)!!,
            sizeBytes = sizeBytes
        )
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> putRaw(
        storeName: String,
        key: String,
        value: T,
        serializer: KSerializer<T>,
    ): StoredObject<T> {
        val serialized = objectSerializer.serialize(serializer, value)
        val now = timestampProvider.getTimestamp()

        driver.execute(null, """
            INSERT INTO $tableName (key, value, store_name, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(store_name, key) DO UPDATE SET
                value = excluded.value,
                updated_at = excluded.updated_at
        """.trimIndent(), 5) {
            bindString(0, key)
            when (serialized) {
                is String -> bindString(1, serialized)
                is ByteArray -> bindBytes(1, serialized)
            }
            bindString(2, storeName)
            bindLong(3, now)
            bindLong(4, now)
        }

        return getRaw(
            storeName = storeName,
            key = key,
            type = value::class as KClass<T>,
            serializer = serializer,
        ) ?: error("Object disappeared immediately after put: $storeName/$key")
    }

    override suspend fun <T : Any> getRaw(
        storeName: String, key: String,
        type: KClass<T>, serializer: KSerializer<T>
    ): StoredObject<T>? {
        return driver.executeQuery(
            null,
            "SELECT * FROM $tableName WHERE key = ? AND store_name = ?",
            { cursor -> QueryResult.Value(readRow(cursor, serializer)) },
            2
        ) {
            bindString(0, key)
            bindString(1, storeName)
        }.value
    }

    override suspend fun <T : Any> getAllRaw(
        storeName: String, type: KClass<T>, serializer: KSerializer<T>
    ): List<StoredObject<T>> {
        return driver.executeQuery(
            null,
            "SELECT * FROM $tableName WHERE store_name = ?",
            { cursor ->
                val items = mutableListOf<StoredObject<T>>()
                while (true) {
                    items.add(readRow(cursor, serializer) ?: break)
                }
                QueryResult.Value(items)
            },
            1
        ) {
            bindString(0, storeName)
        }.value
    }

    override suspend fun deleteRaw(storeName: String, key: String) {
        driver.execute(
            null,
            "DELETE FROM $tableName WHERE key = ? AND store_name = ?",
            2
        ) {
            bindString(0, key)
            bindString(1, storeName)
        }
    }

    override suspend fun clearRaw(storeName: String) {
        driver.execute(null, "DELETE FROM $tableName WHERE store_name = ?", 1) {
            bindString(0, storeName)
        }
    }

    override suspend fun clearRaw() {
        driver.execute(null, "DELETE FROM $tableName", 0)
    }
}
