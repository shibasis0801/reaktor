package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface Wire {
    fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    fun reply(reply: Reply, output: SerialDescriptor): Reply = reply

    data object Plain : Wire

    class Tagged(
        private val variant: (status: Int) -> String,
        private val reason: String,
        private val fields: (status: Int) -> Map<String, JsonElement> = { emptyMap() },
    ) : Wire {
        override fun <T> encode(serializer: KSerializer<T>, value: T): String =
            json.encodeToString(JsonElement.serializer(), json.encodeToJsonElement(serializer, value).tagged(serializer.descriptor))

        override fun reply(reply: Reply, output: SerialDescriptor): Reply {
            if (!output.named) return reply
            val problem = (reply.body[Reply.Problem] as? JsonObject)?.takeIf { type in it }
            val family = output.serialName.substringBeforeLast('.', "")
            val shape = problem ?: JsonObject(mapOf(
                type to JsonPrimitive(listOf(family, variant(reply.status)).filter(String::isNotEmpty).joinToString(".")),
                reason to (reply.body[Reply.Error] ?: JsonPrimitive("")),
            ) + fields(reply.status))
            return Reply(reply.status, JsonObject(shape + reply.body), reply.retryAfter)
        }
    }
}

private val type: String get() = json.configuration.classDiscriminator

private val SerialDescriptor.named: Boolean
    get() = kind == StructureKind.CLASS || kind == StructureKind.OBJECT || kind == PolymorphicKind.SEALED

@PublishedApi
internal fun JsonElement.tagged(descriptor: SerialDescriptor): JsonElement =
    if (this is JsonObject && descriptor.named && type !in this) JsonObject(mapOf(type to JsonPrimitive(descriptor.serialName)) + this) else this
