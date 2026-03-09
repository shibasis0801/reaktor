package dev.shibasis.reaktor.experiments.cloudflarehello.chat

import dev.shibasis.reaktor.cloudflare.CloudflareDurableObject
import dev.shibasis.reaktor.cloudflare.DurableObjectNamespace
import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.serialization.Serializable
import kotlin.js.JsExport
import kotlin.js.Promise

@Serializable
data class ReservedMessageSequence(
    val roomId: String,
    val sequence: Int,
    val presence: ChatPresence,
    val reservedAt: String,
)

@Serializable
private data class JoinRoomCommand(
    val roomId: String,
    val participant: ChatParticipant,
)

@Serializable
private data class LeaveRoomCommand(
    val roomId: String,
    val participantId: String,
)

@Serializable
private data class PublishMessageCommand(
    val roomId: String,
    val participant: ChatParticipant,
)

@Serializable
private data class CoordinatorState(
    val participants: List<ChatParticipant> = emptyList(),
    val latestSequence: Int = 0,
    val updatedAt: String = nowIsoString(),
)

class ChatRoomCoordinator(
    private val namespace: DurableObjectNamespace,
) {
    suspend fun snapshot(roomId: String): ChatPresence =
        namespace.named(roomId).getJson("https://chat.invalid/snapshot?roomId=$roomId")

    suspend fun join(roomId: String, participant: ChatParticipant): ChatPresence =
        namespace.named(roomId).postJson(
            "https://chat.invalid/join",
            JoinRoomCommand(roomId, participant),
        )

    suspend fun leave(roomId: String, participantId: String): ChatPresence =
        namespace.named(roomId).postJson(
            "https://chat.invalid/leave",
            LeaveRoomCommand(roomId, participantId),
        )

    suspend fun reserveSequence(roomId: String, participant: ChatParticipant): ReservedMessageSequence =
        namespace.named(roomId).postJson(
            "https://chat.invalid/publish",
            PublishMessageCommand(roomId, participant),
        )
}

@JsExport
@OptIn(DelicateCoroutinesApi::class)
class ChatRoomDurableObject(
    state: Any,
    env: Any,
) : CloudflareDurableObject(state, env) {
    fun fetch(request: Any): Promise<dynamic> = GlobalScope.promise {
        val incoming = incomingRequest(request)
        when (incoming.path) {
            "/snapshot" -> json(loadState().toPresence(incoming.requireQuery("roomId")))
            "/join" -> {
                val command = incoming.decode<JoinRoomCommand>()
                val state = loadState()
                    .upsert(command.participant)
                    .touch()
                    .persist()

                json(state.toPresence(command.roomId))
            }

            "/leave" -> {
                val command = incoming.decode<LeaveRoomCommand>()
                val state = loadState()
                    .remove(command.participantId)
                    .touch()
                    .persist()

                json(state.toPresence(command.roomId))
            }

            "/publish" -> {
                val command = incoming.decode<PublishMessageCommand>()
                val state = loadState()
                    .upsert(command.participant)
                    .increment()
                    .touch()
                    .persist()

                json(
                    ReservedMessageSequence(
                        roomId = command.roomId,
                        sequence = state.latestSequence,
                        presence = state.toPresence(command.roomId),
                        reservedAt = state.updatedAt,
                    ),
                )
            }

            else -> text("Unknown coordinator route '${incoming.path}'")
        }
    }

    private suspend fun loadState(): CoordinatorState =
        storage.text("state")?.let(json::decodeFromString) ?: CoordinatorState()

    private suspend fun CoordinatorState.persist(): CoordinatorState {
        storage.putText("state", json.encodeToString(this))
        return this
    }

    private fun CoordinatorState.upsert(participant: ChatParticipant): CoordinatorState =
        copy(
            participants = (participants.filterNot { it.id == participant.id } + participant).sortedBy(ChatParticipant::displayName),
        )

    private fun CoordinatorState.remove(participantId: String): CoordinatorState =
        copy(
            participants = participants.filterNot { it.id == participantId },
        )

    private fun CoordinatorState.increment(): CoordinatorState =
        copy(
            latestSequence = latestSequence + 1,
        )

    private fun CoordinatorState.touch(): CoordinatorState =
        copy(
            updatedAt = nowIsoString(),
        )

    private fun CoordinatorState.toPresence(roomId: String): ChatPresence =
        ChatPresence(
            roomId = roomId,
            participants = participants,
            activeParticipants = participants.size,
            latestSequence = latestSequence,
            updatedAt = updatedAt,
        )
}
