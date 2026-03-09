package dev.shibasis.reaktor.experiments.cloudflarehello.chat

import dev.shibasis.reaktor.cloudflare.CloudflareRequest
import dev.shibasis.reaktor.graph.service.Response
import kotlinx.serialization.Serializable

@Serializable
data class ChatParticipant(
    val id: String,
    val displayName: String,
)

@Serializable
data class ChatMediaRef(
    val id: String,
    val key: String,
    val fileName: String,
    val contentType: String,
    val uploadedBy: ChatParticipant,
    val uploadedAt: String,
)

@Serializable
data class ChatMessage(
    val id: String,
    val roomId: String,
    val sequence: Int,
    val author: ChatParticipant,
    val body: String,
    val media: ChatMediaRef? = null,
    val createdAt: String,
)

@Serializable
data class ChatPresence(
    val roomId: String,
    val participants: List<ChatParticipant>,
    val activeParticipants: Int,
    val latestSequence: Int,
    val updatedAt: String,
)

@Serializable
data class ChatRoomSummary(
    val id: String,
    val name: String,
    val topic: String,
    val createdBy: ChatParticipant,
    val createdAt: String,
    val activeParticipants: Int = 0,
    val latestSequence: Int = 0,
)

@Serializable
class ChatOverviewRequest : CloudflareRequest()

@Serializable
class ChatOverviewResponse(
    val message: String,
    val routes: List<String>,
    val flow: List<String>,
) : Response()

@Serializable
class ListRoomsRequest : CloudflareRequest()

@Serializable
class ChatRoomsResponse(
    val rooms: List<ChatRoomSummary>,
) : Response()

@Serializable
class CreateRoomRequest(
    val name: String,
    val topic: String,
    val createdBy: ChatParticipant,
    val seedMembers: List<ChatParticipant> = emptyList(),
) : CloudflareRequest()

@Serializable
class RoomRequest : CloudflareRequest()

@Serializable
class RoomMembershipRequest(
    val participant: ChatParticipant,
) : CloudflareRequest()

@Serializable
class SendMessageRequest(
    val author: ChatParticipant,
    val body: String,
    val mediaId: String? = null,
) : CloudflareRequest()

@Serializable
class RoomMessagesRequest : CloudflareRequest()

@Serializable
class UploadMediaRequest(
    val uploadedBy: ChatParticipant,
    val fileName: String,
    val contentType: String,
    val base64Data: String,
) : CloudflareRequest()

@Serializable
class MediaRequest : CloudflareRequest()

@Serializable
class ChatRoomStateResponse(
    val room: ChatRoomSummary,
    val presence: ChatPresence,
    val messages: List<ChatMessage>,
) : Response()

@Serializable
class ChatMessageResponse(
    val message: ChatMessage,
    val presence: ChatPresence,
) : Response()

@Serializable
class ChatMediaResponse(
    val media: ChatMediaRef,
) : Response()

@Serializable
class ChatMediaDownloadResponse(
    val media: ChatMediaRef,
    val base64Data: String,
) : Response()
