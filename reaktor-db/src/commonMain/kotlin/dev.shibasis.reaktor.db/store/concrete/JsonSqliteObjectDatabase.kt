package dev.shibasis.reaktor.db.store.concrete

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.shibasis.reaktor.io.serialization.BinarySerializer
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import dev.shibasis.reaktor.io.serialization.TextSerializer
import dev.shibasis.reaktor.db.store.CachePolicy
import dev.shibasis.reaktor.db.store.DefaultTimestampProvider
import dev.shibasis.reaktor.db.store.ObjectDatabase
import dev.shibasis.reaktor.db.store.StoredObject
import dev.shibasis.reaktor.db.store.TimestampProvider
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

// hide with factory pattern
class SqliteObjectDatabase(
    private val driver: SqlDriver,
    name: String,
    objectSerializer: ObjectSerializer<*> = TextSerializer(),
    cachePolicy: CachePolicy = LRUCachePolicy(100),
    timestampProvider: TimestampProvider = DefaultTimestampProvider()
): ObjectDatabase(objectSerializer, cachePolicy, timestampProvider) {
    private val tableName = "object_db_$name"

    companion object {
        const val KEY_COLUMN = "key"
        const val VALUE_COLUMN = "value"
        const val STORE_NAME_COLUMN = "store_name"
        const val CREATED_AT_COLUMN = "created_at"
        const val UPDATED_AT_COLUMN = "updated_at"
    }

    private fun createTable(tableName: String) {
        val valueColumnType =  objectSerializer.choose("TEXT", "BLOB")

        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS $tableName (
                $KEY_COLUMN TEXT NOT NULL,
                $VALUE_COLUMN $valueColumnType NOT NULL,
                $STORE_NAME_COLUMN TEXT NOT NULL,
                $CREATED_AT_COLUMN INTEGER NOT NULL,
                $UPDATED_AT_COLUMN INTEGER NOT NULL,
                PRIMARY KEY ($KEY_COLUMN, $STORE_NAME_COLUMN)
            )
            """.trimIndent(),
            0
        )
    }

    init {
        createTable(tableName)
    }

    private fun <T : Any> mapToStoredObject(
        cursor: SqlCursor,
        serializer: KSerializer<T>
    ): StoredObject<T>? {
        if (!cursor.next().value) return null

        val key = cursor.getString(0)!!
        val value = when(objectSerializer) {
            is BinarySerializer -> objectSerializer.deserialize(serializer, cursor.getBytes(1)!!)
            is TextSerializer -> objectSerializer.deserialize(serializer, cursor.getString(1)!!)
        }
        val storeName = cursor.getString(2)!!
        val createdAt = cursor.getLong(3)!!
        val updatedAt = cursor.getLong(4)!!

        return StoredObject(key, value, storeName, createdAt, updatedAt)
    }

    override suspend fun <T : Any> put(storeName: String, key: String, value: T, serializer: KSerializer<T>) {
        val serializedValue = objectSerializer.serialize(serializer, value)

        val now = timestampProvider.getTimestamp()

        driver.execute(
            null,
            "INSERT OR REPLACE INTO $tableName ($KEY_COLUMN, $VALUE_COLUMN, $STORE_NAME_COLUMN, $CREATED_AT_COLUMN, $UPDATED_AT_COLUMN) VALUES (?, ?, ?, ?, ?)",
            6
        ) {
            bindString(0, key)
            when (serializedValue) {
                is String -> bindString(1, serializedValue)
                is ByteArray -> bindBytes(1, serializedValue)
            }
            bindString(2, storeName)
            bindLong(3, now)
            bindLong(4, now)
        }

        val rowChanges = driver.executeQuery(null, "SELECT changes()", { cursor ->
            QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L)
        }, 0, null).value

        if (rowChanges) {
            cachePolicy.onItemInsertion(key, storeName)
        } else {
            cachePolicy.onItemUpdate(key, storeName)
        }

        // Trigger eviction if needed
        val keysToEvict = cachePolicy.findKeysToEvict(storeName)
        keysToEvict.forEach { (keyToEvict, storeNameToEvictFrom) ->
            delete(keyToEvict, storeNameToEvictFrom)
        }
    }

    override suspend fun <T : Any> get(
        storeName: String,
        key: String,
        type: KClass<T>,
        serializer: KSerializer<T>
    ): StoredObject<T>? {
        val result = driver.executeQuery(
            null,
            "SELECT * FROM $tableName WHERE $KEY_COLUMN = ? AND $STORE_NAME_COLUMN = ?",
            { cursor ->
                QueryResult.Value(
                    mapToStoredObject(
                        cursor,
                        serializer
                    )
                )
            },
            2
        ) {
            bindString(0, key)
            bindString(1, storeName)
        }

        val value = result.value ?: return null

        val cached = cachePolicy.onItemAccess(value)
        return if (cached == null) {
            delete(storeName, key)
            null
        }
        else cached
    }

    override suspend fun <T : Any> getAll(
        storeName: String,
        type: KClass<T>,
        serializer: KSerializer<T>
    ): List<StoredObject<T>> {
        val result = driver.executeQuery(
            null,
            "SELECT * FROM $tableName WHERE $STORE_NAME_COLUMN = ?",
            { cursor ->
                val items = mutableListOf<StoredObject<T>>()
                var storedObject = mapToStoredObject(
                    cursor,
                    serializer
                )
                while (storedObject != null) {
                    items.add(storedObject)
                    storedObject = mapToStoredObject(
                        cursor,
                        serializer
                    )
                }
                QueryResult.Value(items)
            },
            1
        ) {
            bindString(0, storeName)
        }
        result.value.forEach { cachePolicy.onItemAccess(it) }
        return result.value
    }

    override suspend fun delete(storeName: String, key: String) {
        driver.execute(
            null,
            "DELETE FROM $tableName WHERE \"$KEY_COLUMN\" = ? AND $STORE_NAME_COLUMN = ?",
            2
        ) {
            bindString(0, key)
            bindString(1, storeName)
        }
        cachePolicy.onItemDeletion(key, storeName)
    }

    override suspend fun clear(storeName: String) {
        driver.execute(
            null,
            "DELETE FROM $tableName WHERE $STORE_NAME_COLUMN = ?",
            1
        ) {
            bindString(0, storeName)
        }
    }

    override suspend fun clear() {
        driver.execute(
            null,
            "DELETE FROM $tableName",
            0
        )
    }
}
