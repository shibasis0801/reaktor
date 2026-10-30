package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DurableObjectMutation(val instance: String, val instanceId: Boolean, val change: DurableObjectChange) {
    fun validate() {
        require(instance.isNotBlank() && instance.length <= 4096 && '\u0000' !in instance) { "Select one named instance or object ID" }
        require(!instanceId || instance.matches(Regex("[a-fA-F0-9]{64}"))) { "Durable Object IDs contain 64 hexadecimal characters" }
        when (val command = change) {
            is DurableObjectChange.Storage -> {
                require(command.key.encodeToByteArray().size in 1..2048 && '\u0000' !in command.key) { "Storage keys must contain 1–2048 bytes" }
                require(command.value.toString().encodeToByteArray().size <= 131_072) { "Storage edits are limited to 128 KiB of JSON" }
            }
            is DurableObjectChange.Alarm -> require(command.atMillis == null || command.atMillis in 1..8_640_000_000_000_000) { "Use a valid alarm timestamp or clear the alarm" }
            DurableObjectChange.Disconnect -> Unit
        }
    }
}

@Serializable
sealed interface DurableObjectChange {
    @Serializable
    @SerialName("storage")
    data class Storage(val key: String, val value: JsonElement) : DurableObjectChange

    @Serializable
    @SerialName("alarm")
    data class Alarm(val atMillis: Long?) : DurableObjectChange

    @Serializable
    @SerialName("disconnect")
    data object Disconnect : DurableObjectChange
}
