package dev.shibasis.reaktor.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.shibasis.reaktor.core.annotations.Expose

// Move to common
enum class StatusCode(val code: Int) {
    SUCCESS(200),
    ERROR_CLIENT(400),
    ERROR_RATE_LIMIT(429),
    ERROR_SERVER(500)
}

@Expose
@Serializable
data class Response(
    val jsonData: String,
    val statusCode: StatusCode = StatusCode.SUCCESS
)

inline fun<reified T> JsonResponse(
    data: T,
    statusCode: StatusCode = StatusCode.SUCCESS,
) = Response(Json.encodeToString<T>(data), statusCode)
