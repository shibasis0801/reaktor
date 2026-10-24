package dev.shibasis.reaktor.service.contract

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import dev.shibasis.reaktor.service.HttpFailure
import dev.shibasis.reaktor.service.Response
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

sealed class CallFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    abstract val status: Int

    var answer: Response? = null
        internal set
}

class Rejected(
    override val status: Int,
    message: String,
    val problem: JsonElement? = null,
    cause: Throwable? = null,
) : CallFailure(message, cause) {
    init {
        require(status in 400..499) { "A rejection is a 4xx answer, not $status" }
    }

    inline fun <reified P> problemAs(): P? = problem?.let { runCatching { json.decodeFromJsonElement(kSerializer<P>(), it) }.getOrNull() }
}

class Unavailable(
    message: String,
    override val status: Int = 503,
    val retryAfter: Duration? = null,
    cause: Throwable? = null,
) : CallFailure(message, cause)

class OutcomeUnknown(
    message: String,
    override val status: Int = 504,
    cause: Throwable? = null,
) : CallFailure(message, cause)

class ContractDrift(
    message: String,
    override val status: Int,
    cause: Throwable? = null,
) : CallFailure(message, cause)

inline fun <reified P> reject(problem: P, status: Int = 409, message: String = "Rejected"): Nothing =
    throw Rejected(status, message, json.encodeToJsonElement(kSerializer<P>(), problem))

fun reject(status: Int, message: String): Nothing = throw Rejected(status, message)

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Rejection(val failure: Rejected) : Outcome<Nothing>
    data class Failure(val failure: Unavailable) : Outcome<Nothing>
    data class Unknown(val failure: CallFailure) : Outcome<Nothing>

    val valueOrNull: T? get() = (this as? Success<T>)?.value
}

suspend fun <T> outcome(block: suspend () -> T): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    when (val classified = failure.asCallFailure()) {
        is Rejected -> Outcome.Rejection(classified)
        is Unavailable -> Outcome.Failure(classified)
        is OutcomeUnknown, is ContractDrift -> Outcome.Unknown(classified)
    }
}

fun Throwable.asCallFailure(): CallFailure = when (this) {
    is CallFailure -> this
    is HttpFailure -> if (statusCode.code in 400..499) Rejected(statusCode.code, message ?: statusCode.name, cause = this)
        else OutcomeUnknown(message ?: statusCode.name, statusCode.code, this)
    else -> OutcomeUnknown(message ?: this::class.simpleName ?: "failed", 500, this)
}

internal fun statusOf(result: Any?, fallback: Int): Int = (result as? Response)?.transportStatusCode?.code ?: fallback

class Reply(val status: Int, val body: JsonObject, val retryAfter: Duration? = null) {
    companion object {
        fun of(status: Int, message: String, problem: JsonElement? = null, retryAfter: Duration? = null) = Reply(
            status,
            JsonObject(buildMap {
                put("error", JsonPrimitive(message))
                if (problem != null && problem !is JsonNull) put("problem", problem)
            }),
            retryAfter,
        )
    }
}

fun Throwable.reply(subject: String): Reply = when (this) {
    is Rejected -> Reply.of(status, message ?: "Rejected", problem)
    is Unavailable -> Reply.of(status, message ?: "Unavailable", retryAfter = retryAfter)
    is OutcomeUnknown -> Reply.of(status, message ?: "Outcome unknown")
    is ContractDrift -> Reply.of(502, message ?: "Contract drift")
    is HttpFailure -> Reply.of(statusCode.code, message ?: statusCode.name)
    else -> {
        Logger.e(this) { "Unhandled failure serving $subject" }
        Reply.of(500, "Internal server error")
    }
}

fun failureOf(status: Int, body: JsonElement?, subject: String, retryAfter: Duration? = null): CallFailure {
    val reply = body as? JsonObject
    val message = (reply?.get("error") as? JsonPrimitive)?.content ?: "$subject answered $status"
    return when {
        status == 408 || status == 429 || status == 501 || status == 503 -> Unavailable(message, status, retryAfter)
        status in 400..499 -> Rejected(status, message, reply?.get("problem"))
        else -> OutcomeUnknown(message, status)
    }
}
