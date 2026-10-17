package dev.shibasis.reaktor.tooling.database

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.*

enum class MessageDecoder { Text, Json, Base64Utf8, Hex }

object MessagePayload {
    fun identity(values: List<String?>): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(JsonArray(values.map { it?.let(::JsonPrimitive) ?: JsonNull }).toString().toByteArray()).toHexString()
    fun decode(value: String, decoder: MessageDecoder): String {
        require(value.toByteArray().size <= 1_048_576) { "Payload exceeds the 1 MiB decoder preview budget" }
        return when (decoder) {
            MessageDecoder.Text -> value
            MessageDecoder.Json -> Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(value))
            MessageDecoder.Base64Utf8 -> StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getDecoder().decode(value))).toString()
            MessageDecoder.Hex -> value.toByteArray(StandardCharsets.UTF_8).toHexString(HexFormat { bytes { byteSeparator = " " } })
        }
    }
}
