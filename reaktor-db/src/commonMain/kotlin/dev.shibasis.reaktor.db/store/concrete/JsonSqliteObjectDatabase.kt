package dev.shibasis.reaktor.db.store.concrete

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.shibasis.reaktor.db.store.CachePolicy
import dev.shibasis.reaktor.db.store.DefaultTimestampProvider
import dev.shibasis.reaktor.db.store.FlexSerializer
import dev.shibasis.reaktor.db.store.JsonSerializer
import dev.shibasis.reaktor.db.store.ObjectDatabase
import dev.shibasis.reaktor.db.store.ObjectSerializer
import dev.shibasis.reaktor.db.store.StoredObject
import dev.shibasis.reaktor.db.store.TimestampProvider
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass

class SqliteObjectDatabase(
    private val driver: SqlDriver,
    private val tableName: String,
    objectSerializer: ObjectSerializer<*> = JsonSerializer(),
    cachePolicy: CachePolicy,
    timestampProvider: TimestampProvider = DefaultTimestampProvider(),
): ObjectDatabase(objectSerializer, cachePolicy, timestampProvider) {
    companion object {
        const val KEY_COLUMN = "key"
        const val VALUE_COLUMN = "value"
        const val TYPE_COLUMN = "type"
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
                $TYPE_COLUMN TEXT NOT NULL,
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
            is FlexSerializer -> objectSerializer.deserialize(serializer, cursor.getBytes(1)!!)
            is JsonSerializer -> objectSerializer.deserialize(serializer, cursor.getString(1)!!)
        }


        val type = cursor.getString(2)!!
        val storeName = cursor.getString(3)!!
        val createdAt = cursor.getLong(4)!!
        val updatedAt = cursor.getLong(5)!!

        return StoredObject(key, value, type, storeName, createdAt, updatedAt)
    }

    override suspend fun <T : Any> put(key: String, value: T, serializer: KSerializer<T>, storeName: String) {
        val serializedValue = objectSerializer.serialize(serializer, value)

        val now = timestampProvider.getTimestamp()

        driver.execute(
            null,
            "INSERT OR REPLACE INTO $tableName ($KEY_COLUMN, $VALUE_COLUMN, $TYPE_COLUMN, $STORE_NAME_COLUMN, $CREATED_AT_COLUMN, $UPDATED_AT_COLUMN) VALUES (?, ?, ?, ?, ?, ?)",
            6
        ) {
            bindString(0, key)
            when (serializedValue) {
                is String -> bindString(1, serializedValue)
                is ByteArray -> bindBytes(1, serializedValue)
            }
            bindString(2, value::class.simpleName)
            bindString(3, storeName)
            bindLong(4, now)
            bindLong(5, now)
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
        key: String,
        storeName: String,
        type: KClass<T>,
        serializer: KSerializer<T>
    ): StoredObject<T>? {
        val result = driver.executeQuery(
            null,
            "SELECT * FROM $tableName WHERE $KEY_COLUMN = ? AND $STORE_NAME_COLUMN = ? AND $TYPE_COLUMN = ?",
            { cursor ->
                QueryResult.Value(
                    mapToStoredObject(
                        cursor,
                        serializer
                    )
                )
            },
            3
        ) {
            bindString(0, key)
            bindString(1, storeName)
            bindString(2, type.simpleName)
        }
        cachePolicy.onItemAccess(key, storeName)
        return result.value
    }

    override suspend fun <T : Any> getAll(
        storeName: String,
        type: KClass<T>,
        serializer: KSerializer<T>
    ): List<StoredObject<T>> {
        val result = driver.executeQuery(
            null,
            "SELECT * FROM $tableName WHERE $STORE_NAME_COLUMN = ? AND $TYPE_COLUMN = ?",
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
            2
        ) {
            bindString(0, storeName)
            bindString(1, type.simpleName)
        }
        result.value.forEach { cachePolicy.onItemAccess(it.key, it.storeName) }
        return result.value
    }

    override suspend fun delete(key: String, storeName: String) {
        driver.execute(
            null,
            "DELETE FROM $tableName WHERE $KEY_COLUMN = ? AND $STORE_NAME_COLUMN = ?",
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