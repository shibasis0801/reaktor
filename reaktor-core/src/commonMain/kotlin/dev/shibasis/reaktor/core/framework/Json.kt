package dev.shibasis.reaktor.core.framework

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.serializer
import kotlin.time.Instant

// Unfortunately we can't change default behaviour as other libraries may rely on it. So these wrappers.

fun JsonBuilder.defaults() {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

var json = Json {
    defaults()
    serializersModule = SerializersModule {
        contextual(Instant::class, InstantAsStringSerializer)
    }
}

val EMPTY_JSON = JsonObject(emptyMap())

inline fun JsonObject.copy(
    fn: MutableMap<String, JsonElement>.() -> Unit
) = JsonObject(toMutableMap().apply(fn))

inline fun<reified T> kSerializer() = json.serializersModule.serializer<T>()

object InstantAsStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlin.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}
