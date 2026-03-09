package dev.shibasis.reaktor.experiments.cloudflarehello.chat

import dev.shibasis.reaktor.cloudflare.D1Database
import dev.shibasis.reaktor.cloudflare.execute
import dev.shibasis.reaktor.cloudflare.getJson
import dev.shibasis.reaktor.cloudflare.putJson
import dev.shibasis.reaktor.cloudflare.rows
import dev.shibasis.reaktor.core.cloudflare.R2Bucket
import kotlinx.serialization.Serializable

private const val roomsTable = "experiment_chat_rooms"
private const val messagesTable = "experiment_chat_messages"
private const val createRoomsTableStatement =
    "CREATE TABLE IF NOT EXISTS experiment_chat_rooms (" +
        "id TEXT PRIMARY KEY, " +
        "name TEXT NOT NULL, " +
        "topic TEXT NOT NULL, " +
        "created_by_id TEXT NOT NULL, " +
        "created_by_name TEXT NOT NULL, " +
        "created_at TEXT NOT NULL" +
        ");"
private const val createMessagesTableStatement =
    "CREATE TABLE IF NOT EXISTS experiment_chat_messages (" +
        "id TEXT PRIMARY KEY, " +
        "room_id TEXT NOT NULL, " +
        "sequence INTEGER NOT NULL, " +
        "author_id TEXT NOT NULL, " +
        "author_name TEXT NOT NULL, " +
        "body TEXT NOT NULL, " +
        "media_id TEXT, " +
        "media_key TEXT, " +
        "media_file_name TEXT, " +
        "media_content_type TEXT, " +
        "media_uploaded_by_id TEXT, " +
        "media_uploaded_by_name TEXT, " +
        "media_uploaded_at TEXT, " +
        "created_at TEXT NOT NULL, " +
        "FOREIGN KEY(room_id) REFERENCES experiment_chat_rooms(id)" +
        ");"
private const val createRoomSequenceIndexStatement =
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_experiment_chat_room_sequence " +
        "ON experiment_chat_messages(room_id, sequence);"

private var schemaReady = false

@Serializable
private data class StoredMediaEnvelope(
    val media: ChatMediaRef,
    val base64Data: String,
)

class ChatRepository(
    private val database: D1Database,
    private val mediaBucket: R2Bucket,
) {
    suspend fun createRoom(request: CreateRoomRequest): ChatRoomSummary {
        ensureSchema()

        val room = ChatRoomSummary(
            id = nextId("room"),
            name = request.name.trim(),
            topic = request.topic.trim(),
            createdBy = request.createdBy,
            createdAt = nowIsoString(),
        )

        database.prepare(
            """
            INSERT INTO $roomsTable (
                id,
                name,
                topic,
                created_by_id,
                created_by_name,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).bind(
            room.id,
            room.name,
            room.topic,
            room.createdBy.id,
            room.createdBy.displayName,
            room.createdAt,
        ).execute()

        return room
    }

    suspend fun listRooms(limit: Int): List<ChatRoomSummary> {
        ensureSchema()

        val rows = database.prepare(
            """
            SELECT
                id,
                name,
                topic,
                created_by_id,
                created_by_name,
                created_at
            FROM $roomsTable
            ORDER BY created_at DESC
            LIMIT ?
            """.trimIndent(),
        ).bind(limit).rows()

        return rows.map(::roomFromRow)
    }

    suspend fun requireRoom(roomId: String): ChatRoomSummary {
        ensureSchema()

        val row =
            database.prepare(
                """
                SELECT
                    id,
                    name,
                    topic,
                    created_by_id,
                    created_by_name,
                    created_at
                FROM $roomsTable
                WHERE id = ?
                LIMIT 1
                """.trimIndent(),
            ).bind(roomId).rows().firstOrNull()
                ?: error("Room '$roomId' was not found")

        return roomFromRow(row)
    }

    suspend fun saveMessage(
        roomId: String,
        sequence: Int,
        author: ChatParticipant,
        body: String,
        media: ChatMediaRef?,
    ): ChatMessage {
        ensureSchema()

        val message = ChatMessage(
            id = nextId("msg"),
            roomId = roomId,
            sequence = sequence,
            author = author,
            body = body.trim(),
            media = media,
            createdAt = nowIsoString(),
        )

        database.prepare(
            """
            INSERT INTO $messagesTable (
                id,
                room_id,
                sequence,
                author_id,
                author_name,
                body,
                media_id,
                media_key,
                media_file_name,
                media_content_type,
                media_uploaded_by_id,
                media_uploaded_by_name,
                media_uploaded_at,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).bind(
            message.id,
            message.roomId,
            message.sequence,
            message.author.id,
            message.author.displayName,
            message.body,
            message.media?.id,
            message.media?.key,
            message.media?.fileName,
            message.media?.contentType,
            message.media?.uploadedBy?.id,
            message.media?.uploadedBy?.displayName,
            message.media?.uploadedAt,
            message.createdAt,
        ).execute()

        return message
    }

    suspend fun listMessages(
        roomId: String,
        limit: Int,
        beforeSequence: Int? = null,
    ): List<ChatMessage> {
        ensureSchema()

        val rows =
            if (beforeSequence == null) {
                database.prepare(
                    """
                    SELECT *
                    FROM $messagesTable
                    WHERE room_id = ?
                    ORDER BY sequence DESC
                    LIMIT ?
                    """.trimIndent(),
                ).bind(roomId, limit).rows()
            } else {
                database.prepare(
                    """
                    SELECT *
                    FROM $messagesTable
                    WHERE room_id = ?
                      AND sequence < ?
                    ORDER BY sequence DESC
                    LIMIT ?
                    """.trimIndent(),
                ).bind(roomId, beforeSequence, limit).rows()
            }

        return rows.map(::messageFromRow).reversed()
    }

    suspend fun storeMedia(request: UploadMediaRequest): ChatMediaRef {
        ensureSchema()

        val mediaId = nextId("media")
        val media = ChatMediaRef(
            id = mediaId,
            key = "${ChatBindings.mediaPrefix}/$mediaId.json",
            fileName = request.fileName.trim(),
            contentType = request.contentType.trim(),
            uploadedBy = request.uploadedBy,
            uploadedAt = nowIsoString(),
        )

        mediaBucket.putJson(
            media.key,
            StoredMediaEnvelope(
                media = media,
                base64Data = request.base64Data,
            ),
        )

        return media
    }

    suspend fun loadMedia(mediaId: String): ChatMediaDownloadResponse {
        ensureSchema()

        val envelope =
            mediaBucket.getJson<StoredMediaEnvelope>("${ChatBindings.mediaPrefix}/$mediaId.json")
                ?: error("Media '$mediaId' was not found in R2")

        return ChatMediaDownloadResponse(
            media = envelope.media,
            base64Data = envelope.base64Data,
        )
    }

    suspend fun requireMedia(mediaId: String): ChatMediaRef = loadMedia(mediaId).media

    private suspend fun ensureSchema() {
        if (schemaReady) return

        database.prepare(createRoomsTableStatement).execute()
        database.prepare(createMessagesTableStatement).execute()
        database.prepare(createRoomSequenceIndexStatement).execute()

        schemaReady = true
    }

    private fun roomFromRow(row: Any): ChatRoomSummary =
        ChatRoomSummary(
            id = field(row, "id"),
            name = field(row, "name"),
            topic = field(row, "topic"),
            createdBy = ChatParticipant(field(row, "created_by_id"), field(row, "created_by_name")),
            createdAt = field(row, "created_at"),
        )

    private fun messageFromRow(row: Any): ChatMessage {
        val mediaId = fieldOrNull(row, "media_id")
        val media =
            if (mediaId == null) {
                null
            } else {
                ChatMediaRef(
                    id = mediaId,
                    key = field(row, "media_key"),
                    fileName = field(row, "media_file_name"),
                    contentType = field(row, "media_content_type"),
                    uploadedBy = ChatParticipant(field(row, "media_uploaded_by_id"), field(row, "media_uploaded_by_name")),
                    uploadedAt = field(row, "media_uploaded_at"),
                )
            }

        return ChatMessage(
            id = field(row, "id"),
            roomId = field(row, "room_id"),
            sequence = intField(row, "sequence"),
            author = ChatParticipant(field(row, "author_id"), field(row, "author_name")),
            body = field(row, "body"),
            media = media,
            createdAt = field(row, "created_at"),
        )
    }
}

private fun field(row: Any, name: String): String =
    row.asDynamic()[name]?.toString() ?: error("Missing field '$name' in D1 row")

private fun fieldOrNull(row: Any, name: String): String? = row.asDynamic()[name]?.toString()

private fun intField(row: Any, name: String): Int {
    val value = row.asDynamic()[name] ?: error("Missing field '$name' in D1 row")
    return when (value) {
        is Number -> value.toInt()
        else -> value.toString().toInt()
    }
}
