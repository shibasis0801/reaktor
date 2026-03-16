package dev.shibasis.reaktor.graph.service

import dev.shibasis.reaktor.core.framework.kSerializer
import dev.shibasis.reaktor.core.network.StatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlin.js.JsExport

@Serializable(with = JsonResponseSerializer::class)
class JsonResponse(
    val body: JsonElement,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val statusCode: StatusCode = StatusCode.OK,
) : Response(headers, statusCode)

object JsonResponseSerializer : KSerializer<JsonResponse> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: JsonResponse) {
        require(encoder is JsonEncoder) { "JsonResponseSerializer requires JSON" }
        encoder.encodeJsonElement(value.body)
    }

    override fun deserialize(decoder: Decoder): JsonResponse {
        require(decoder is JsonDecoder) { "JsonResponseSerializer requires JSON" }
        return JsonResponse(decoder.decodeJsonElement())
    }
}

inline fun <reified T> jsonResponse(
    payload: T,
    json: Json,
    headers: MutableMap<String, String> = mutableMapOf(),
    statusCode: StatusCode = StatusCode.OK,
): JsonResponse = JsonResponse(
    body = json.encodeToJsonElement(kSerializer<T>(), payload),
    headers = headers,
    statusCode = statusCode,
)
