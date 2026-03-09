package dev.shibasis.reaktor.experiments.cloudflarehello.chat

import dev.shibasis.reaktor.cloudflare.context
import dev.shibasis.reaktor.cloudflare.get
import dev.shibasis.reaktor.cloudflare.CloudflareRequest
import dev.shibasis.reaktor.graph.service.GetHandler
import dev.shibasis.reaktor.graph.service.PostHandler
import dev.shibasis.reaktor.graph.service.Service

class ChatService : Service() {
    init {
        GetHandler<ChatOverviewRequest, ChatOverviewResponse>("/") {
            ChatOverviewResponse(
                message = "Kotlin-first Cloudflare chat demo",
                routes = listOf(
                    "POST /chat/rooms",
                    "GET /chat/rooms",
                    "POST /chat/rooms/{roomId}/members",
                    "POST /chat/rooms/{roomId}/leave",
                    "POST /chat/media",
                    "GET /chat/media/{mediaId}",
                    "POST /chat/rooms/{roomId}/messages",
                    "GET /chat/rooms/{roomId}/messages",
                ),
                flow = listOf(
                    "Create a room",
                    "Join members into the room coordinator",
                    "Upload media into R2",
                    "Send messages that reserve sequence numbers via Durable Objects and persist to D1",
                    "Read room history from D1 with live presence from Durable Objects",
                ),
            )
        }

        GetHandler<ListRoomsRequest, ChatRoomsResponse>("/rooms") { request ->
            val repository = request.repository()
            val coordinator = request.coordinator()
            val rooms =
                repository.listRooms(request.limit(defaultValue = 10))
                    .map { room ->
                        room.withPresence(coordinator.snapshot(room.id))
                    }

            ChatRoomsResponse(rooms)
        }

        PostHandler<CreateRoomRequest, ChatRoomStateResponse>("/rooms") { request ->
            val repository = request.repository()
            val coordinator = request.coordinator()

            val room = repository.createRoom(request)
            var presence = coordinator.join(room.id, request.createdBy)
            request.seedMembers.forEach { member ->
                presence = coordinator.join(room.id, member)
            }

            ChatRoomStateResponse(
                room = room.withPresence(presence),
                presence = presence,
                messages = emptyList(),
            )
        }

        GetHandler<RoomRequest, ChatRoomStateResponse>("/rooms/{roomId}") { request ->
            val repository = request.repository()
            val coordinator = request.coordinator()
            val roomId = request.roomId()
            val presence = coordinator.snapshot(roomId)
            val messages = repository.listMessages(roomId, request.limit(), request.beforeSequence())

            ChatRoomStateResponse(
                room = repository.requireRoom(roomId).withPresence(presence),
                presence = presence,
                messages = messages,
            )
        }

        PostHandler<RoomMembershipRequest, ChatRoomStateResponse>("/rooms/{roomId}/members") { request ->
            val repository = request.repository()
            val coordinator = request.coordinator()
            val roomId = request.roomId()
            val presence = coordinator.join(roomId, request.participant)

            ChatRoomStateResponse(
                room = repository.requireRoom(roomId).withPresence(presence),
                presence = presence,
                messages = repository.listMessages(roomId, request.limit(), request.beforeSequence()),
            )
        }

        PostHandler<RoomMembershipRequest, ChatRoomStateResponse>("/rooms/{roomId}/leave") { request ->
            val repository = request.repository()
            val coordinator = request.coordinator()
            val roomId = request.roomId()
            val presence = coordinator.leave(roomId, request.participant.id)

            ChatRoomStateResponse(
                room = repository.requireRoom(roomId).withPresence(presence),
                presence = presence,
                messages = repository.listMessages(roomId, request.limit(), request.beforeSequence()),
            )
        }

        PostHandler<UploadMediaRequest, ChatMediaResponse>("/media") { request ->
            val media = request.repository().storeMedia(request)
            ChatMediaResponse(media)
        }

        GetHandler<MediaRequest, ChatMediaDownloadResponse>("/media/{mediaId}") { request ->
            request.repository().loadMedia(request.mediaId())
        }

        PostHandler<SendMessageRequest, ChatMessageResponse>("/rooms/{roomId}/messages") { request ->
            val repository = request.repository()
            val coordinator = request.coordinator()
            val roomId = request.roomId()

            repository.requireRoom(roomId)

            val media = request.mediaId?.let { repository.requireMedia(it) }
            val reservation = coordinator.reserveSequence(roomId, request.author)
            val persisted =
                repository.saveMessage(
                    roomId = roomId,
                    sequence = reservation.sequence,
                    author = request.author,
                    body = request.body,
                    media = media,
                )

            ChatMessageResponse(
                message = persisted,
                presence = reservation.presence,
            )
        }

        GetHandler<RoomMessagesRequest, ChatRoomStateResponse>("/rooms/{roomId}/messages") { request ->
            val repository = request.repository()
            val coordinator = request.coordinator()
            val roomId = request.roomId()
            val presence = coordinator.snapshot(roomId)

            ChatRoomStateResponse(
                room = repository.requireRoom(roomId).withPresence(presence),
                presence = presence,
                messages = repository.listMessages(roomId, request.limit(), request.beforeSequence()),
            )
        }
    }
}

private fun ChatRoomSummary.withPresence(presence: ChatPresence): ChatRoomSummary =
    copy(
        activeParticipants = presence.activeParticipants,
        latestSequence = presence.latestSequence,
    )

private fun CloudflareRequest.repository(): ChatRepository =
    ChatRepository(
        database = context[ChatBindings.database],
        mediaBucket = context[ChatBindings.media],
    )

private fun CloudflareRequest.coordinator(): ChatRoomCoordinator =
    ChatRoomCoordinator(context[ChatBindings.coordinator])

private fun CloudflareRequest.roomId(): String =
    pathParams["roomId"] ?: error("roomId path parameter is required")

private fun CloudflareRequest.mediaId(): String =
    pathParams["mediaId"] ?: error("mediaId path parameter is required")

private fun CloudflareRequest.limit(defaultValue: Int = 20): Int =
    queryParams["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: defaultValue

private fun CloudflareRequest.beforeSequence(): Int? =
    queryParams["beforeSequence"]?.toIntOrNull()
