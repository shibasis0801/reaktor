package dev.shibasis.reaktor.db.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.shibasis.reaktor.io.serialization.BinarySerializer
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import dev.shibasis.reaktor.io.serialization.TextSerializer
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.RawObject
import dev.shibasis.reaktor.db.StoredObject
import dev.shibasis.reaktor.db.UnreadableObjectException
import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.warn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.reflect.KClass

class SqliteObjectDatabase(
    private val driver: SqlDriver,
    name: String,
    objectSerializer: ObjectSerializer<*> = TextSerializer(),
    private val timestampProvider: TimestampProvider = DefaultTimestampProvider()
): ObjectDatabase(objectSerializer) {
    private val tableName = "object_db_${name.replace(Regex("[^A-Za-z0-9_]"), "_")}"
    private val log = "SqliteObjectDatabase".logger()

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

    /**
     * Decodes the row the cursor already sits on.
     *
     * A row outlives the shape that wrote it: a model gains a required field, a later build
     * stores a type differently, a write lands half-finished. That is reported as
     * [UnreadableObjectException] rather than a bare serialization failure, because the two mean
     * different things to a caller — one row of a store is skippable, one keyed document is not.
     */
    private fun <T : Any> decodeRow(cursor: SqlCursor, serializer: KSerializer<T>): StoredObject<T> {
        val key = cursor.getString(0)!!
        val rowStoreName = cursor.getString(2)!!
        val (value, sizeBytes) = try {
            when (objectSerializer) {
                is BinarySerializer -> {
                    val bytes = cursor.getBytes(1)!!
                    objectSerializer.deserialize(serializer, bytes) to bytes.size.toLong()
                }
                is TextSerializer -> {
                    val text = cursor.getString(1)!!
                    objectSerializer.deserialize(serializer, text) to (text.length.toLong() * 2)
                }
            }
        } catch (exception: SerializationException) {
            throw UnreadableObjectException(rowStoreName, key, exception)
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
            { cursor ->
                QueryResult.Value(if (cursor.next().value) decodeRow(cursor, serializer) else null)
            },
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
                while (cursor.next().value) {
                    try {
                        items.add(decodeRow(cursor, serializer))
                    } catch (exception: UnreadableObjectException) {
                        // One row is not worth the other rows. The payload stays in the table,
                        // so a backup still carries it and a later write replaces it.
                        log.warn { "Skipping unreadable row in $tableName: ${exception.message}" }
                    }
                }
                QueryResult.Value(items)
            },
            1
        ) {
            bindString(0, storeName)
        }.value
    }

    override suspend fun exportRaw(storeName: String?): List<RawObject> {
        requireText("export")
        val filtered = storeName != null
        val sql = buildString {
            append("SELECT key, value, store_name, created_at, updated_at FROM $tableName")
            if (filtered) append(" WHERE store_name = ?")
        }

        return driver.executeQuery(
            null,
            sql,
            { cursor ->
                val items = mutableListOf<RawObject>()
                while (cursor.next().value) {
                    items.add(
                        RawObject(
                            key = cursor.getString(0)!!,
                            payload = cursor.getString(1)!!,
                            storeName = cursor.getString(2)!!,
                            createdAt = cursor.getLong(3)!!,
                            updatedAt = cursor.getLong(4)!!,
                        )
                    )
                }
                QueryResult.Value(items)
            },
            if (filtered) 1 else 0
        ) {
            if (storeName != null) bindString(0, storeName)
        }.value
    }

    override suspend fun importRawInternal(items: List<RawObject>) {
        requireText("import")
        items.forEach { item ->
            driver.execute(null, """
                INSERT INTO $tableName (key, value, store_name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(store_name, key) DO UPDATE SET
                    value = excluded.value,
                    updated_at = excluded.updated_at
            """.trimIndent(), 5) {
                bindString(0, item.key)
                bindString(1, item.payload)
                bindString(2, item.storeName)
                bindLong(3, item.createdAt)
                bindLong(4, item.updatedAt)
            }
        }
    }

    /** Raw payloads travel as strings, which a [BinarySerializer] cannot round-trip losslessly. */
    private fun requireText(action: String) {
        if (objectSerializer !is TextSerializer) {
            throw UnsupportedOperationException(
                "Raw $action needs a text serializer; $tableName is stored with " +
                    "${objectSerializer::class.simpleName}.",
            )
        }
    }

    override suspend fun renameRaw(storeName: String, key: String, newKey: String): Boolean {
        // OR IGNORE so a name already taken leaves the original row where it is, rather than
        // failing the rename in a way that loses track of which key still holds the payload.
        val affected = driver.execute(
            null,
            "UPDATE OR IGNORE $tableName SET key = ? WHERE key = ? AND store_name = ?",
            3,
        ) {
            bindString(0, newKey)
            bindString(1, key)
            bindString(2, storeName)
        }.value
        return affected > 0
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
