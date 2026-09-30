package dev.shibasis.reaktor.db.core

import dev.shibasis.reaktor.core.structs.ConcurrentHashMap
import dev.shibasis.reaktor.db.ObjectAddress
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.RawObject
import dev.shibasis.reaktor.db.StoredObject
import dev.shibasis.reaktor.db.UnreadableObjectException
import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.warn
import dev.shibasis.reaktor.io.serialization.ObjectSerializer
import dev.shibasis.reaktor.io.serialization.TextSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.reflect.KClass

/**
 * An [ObjectDatabase] that keeps everything in memory and nothing anywhere else.
 *
 * Two jobs. In a test it is the store a node was written against, without a driver, a file or a
 * temporary directory — and unlike a map of live objects it **serializes on the way in and
 * deserializes on the way out**, so a model that cannot survive a round trip fails here rather than
 * on the first launch of a real build. That is most of the value: a fake that hands back the
 * instance it was given proves that the code stores things, which was never in doubt.
 *
 * In a shell it is the honest answer when there is nowhere to persist to — a preview, a kiosk, a
 * web build with storage refused. Everything above the port works; nothing survives the process,
 * which is a sentence the app can put on screen.
 *
 * Not a cache in front of another database. It answers only from what it holds.
 */
class MemoryObjectDatabase(
    objectSerializer: ObjectSerializer<*> = TextSerializer(),
    private val timestampProvider: TimestampProvider = DefaultTimestampProvider(),
) : ObjectDatabase(objectSerializer) {

    private data class Row(
        val payload: Any,
        val storeName: String,
        val key: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private val rows = ConcurrentHashMap<ObjectAddress, Row>()
    private val log = "MemoryObjectDatabase".logger()

    /** How many rows a store holds, without deserializing any of them. */
    fun size(storeName: String): Int = rows.keys().count { it.storeName == storeName }

    override suspend fun <T : Any> putRaw(
        storeName: String,
        key: String,
        value: T,
        serializer: KSerializer<T>,
    ): StoredObject<T> {
        val address = ObjectAddress(storeName, key)
        val now = timestampProvider.getTimestamp()
        val previous = rows[address]

        rows[address] = Row(
            payload = objectSerializer.serialize(serializer, value) as Any,
            storeName = storeName,
            key = key,
            // Kept from the row being replaced, so "when was this first written" survives an update
            // here the same way it does in a table.
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
        )

        return decode(rows[address] ?: error("Row vanished immediately after put: $storeName/$key"), serializer)
    }

    override suspend fun <T : Any> getRaw(
        storeName: String,
        key: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): StoredObject<T>? = rows[ObjectAddress(storeName, key)]?.let { decode(it, serializer) }

    override suspend fun <T : Any> getAllRaw(
        storeName: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): List<StoredObject<T>> = rows.values()
        .filter { it.storeName == storeName }
        .sortedBy { it.createdAt }
        .mapNotNull { row ->
            try {
                decode(row, serializer)
            } catch (exception: UnreadableObjectException) {
                // One row is not worth the other rows, and it stays where it is: a later write
                // replaces it, and an export still carries it.
                log.warn { "Skipping unreadable row in $storeName: ${exception.message}" }
                null
            }
        }

    override suspend fun deleteRaw(storeName: String, key: String) {
        rows.remove(ObjectAddress(storeName, key))
    }

    override suspend fun clearRaw(storeName: String) {
        rows.keys().filter { it.storeName == storeName }.forEach { rows.remove(it) }
    }

    override suspend fun clearRaw() {
        rows.clear()
    }

    override suspend fun exportRaw(storeName: String?): List<RawObject> = rows.values()
        .filter { storeName == null || it.storeName == storeName }
        .sortedBy { it.createdAt }
        .map { row ->
            RawObject(
                key = row.key,
                payload = row.payload as? String
                    ?: error("A binary store cannot be exported as text"),
                storeName = row.storeName,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
            )
        }

    override suspend fun importRawInternal(items: List<RawObject>) {
        items.forEach { item ->
            rows[ObjectAddress(item.storeName, item.key)] = Row(
                payload = item.payload,
                storeName = item.storeName,
                key = item.key,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> decode(row: Row, serializer: KSerializer<T>): StoredObject<T> {
        val value = try {
            when (val payload = row.payload) {
                is String -> (objectSerializer as ObjectSerializer<String>).deserialize(serializer, payload)
                is ByteArray -> (objectSerializer as ObjectSerializer<ByteArray>).deserialize(serializer, payload)
                else -> error("Unsupported payload ${payload::class}")
            }
        } catch (exception: SerializationException) {
            // The same distinction the SQL-backed store makes: a row this build cannot read is a
            // different thing from a row that is not there, and only the caller knows which of the
            // two it can carry on from.
            throw UnreadableObjectException(row.storeName, row.key, exception)
        }

        return StoredObject(
            key = row.key,
            value = value,
            storeName = row.storeName,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
            sizeBytes = when (val payload = row.payload) {
                is String -> payload.length.toLong() * 2
                is ByteArray -> payload.size.toLong()
                else -> 0L
            },
        )
    }
}
