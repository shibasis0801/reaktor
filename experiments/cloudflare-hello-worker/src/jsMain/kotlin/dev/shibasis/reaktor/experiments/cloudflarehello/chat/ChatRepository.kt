package dev.shibasis.reaktor.experiments.cloudflarehello.chat

import dev.shibasis.reaktor.cloudflare.D1Database
import dev.shibasis.reaktor.cloudflare.R2Bucket
import dev.shibasis.reaktor.cloudflare.sqlValues
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

        database.execute(
            """
            INSERT INTO %I (
                id,
                name,
                topic,
                created_by_id,
                created_by_name,
                created_at
            ) VALUES (%L)
            """.trimIndent(),
            roomsTable,
            sqlValues(
                room.id,
                room.name,
                room.topic,
                room.createdBy.id,
                room.createdBy.displayName,
                room.createdAt,
            ),
        )

        return room
    }

    suspend fun listRooms(limit: Int): List<ChatRoomSummary> {
        ensureSchema()

        return database.rows(
            """
            SELECT
                id,
                name,
                topic,
                created_by_id AS "createdBy.id",
                created_by_name AS "createdBy.displayName",
                created_at AS "createdAt"
            FROM %I
            ORDER BY created_at DESC
            LIMIT %V
            """.trimIndent(),
            roomsTable,
            limit,
        )
    }

    suspend fun requireRoom(roomId: String): ChatRoomSummary {
        ensureSchema()

        return database.firstOrNull(
            """
            SELECT
                id,
                name,
                topic,
                created_by_id AS "createdBy.id",
                created_by_name AS "createdBy.displayName",
                created_at AS "createdAt"
            FROM %I
            WHERE id = %V
            LIMIT 1
            """.trimIndent(),
            roomsTable,
            roomId,
        ) ?: error("Room '$roomId' was not found")
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

        database.execute(
            """
            INSERT INTO %I (
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
            ) VALUES (%L)
            """.trimIndent(),
            messagesTable,
            sqlValues(
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
            ),
        )

        return message
    }

    suspend fun listMessages(
        roomId: String,
        limit: Int,
        beforeSequence: Int? = null,
    ): List<ChatMessage> {
        ensureSchema()

        val query =
            if (beforeSequence == null) {
                database.rows<ChatMessage>(
                    """
                    SELECT
                        id,
                        room_id AS "roomId",
                        sequence,
                        author_id AS "author.id",
                        author_name AS "author.displayName",
                        body,
                        media_id AS "media.id",
                        media_key AS "media.key",
                        media_file_name AS "media.fileName",
                        media_content_type AS "media.contentType",
                        media_uploaded_by_id AS "media.uploadedBy.id",
                        media_uploaded_by_name AS "media.uploadedBy.displayName",
                        media_uploaded_at AS "media.uploadedAt",
                        created_at AS "createdAt"
                    FROM %I
                    WHERE room_id = %V
                    ORDER BY sequence DESC
                    LIMIT %V
                    """.trimIndent(),
                    messagesTable,
                    roomId,
                    limit,
                )
            } else {
                database.rows<ChatMessage>(
                    """
                    SELECT
                        id,
                        room_id AS "roomId",
                        sequence,
                        author_id AS "author.id",
                        author_name AS "author.displayName",
                        body,
                        media_id AS "media.id",
                        media_key AS "media.key",
                        media_file_name AS "media.fileName",
                        media_content_type AS "media.contentType",
                        media_uploaded_by_id AS "media.uploadedBy.id",
                        media_uploaded_by_name AS "media.uploadedBy.displayName",
                        media_uploaded_at AS "media.uploadedAt",
                        created_at AS "createdAt"
                    FROM %I
                    WHERE room_id = %V
                      AND sequence < %V
                    ORDER BY sequence DESC
                    LIMIT %V
                    """.trimIndent(),
                    messagesTable,
                    roomId,
                    beforeSequence,
                    limit,
                )
            }

        return query.reversed()
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

        database.execute(createRoomsTableStatement)
        database.execute(createMessagesTableStatement)
        database.execute(createRoomSequenceIndexStatement)

        schemaReady = true
    }
}
