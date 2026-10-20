package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.mcp.door.DoorApproval
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class OperationId(val value: String) {
    init { require(Pattern.matches(value)) { "Operation ids are lowercase domain.verb: $value" } }

    val domain: String get() = value.substringBefore('.')
    val verb: String get() = value.substringAfter('.')

    override fun toString() = value

    companion object {
        val Pattern = Regex("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+")
    }
}

@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Subject

@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class Describe(val text: String)

@Serializable
data class OperationDescriptor(
    val id: OperationId,
    val summary: String,
    val effect: SafetyClass,
    val provider: String,
    val input: JsonObject,
    val output: JsonObject? = null,
    val subject: String? = null,
)

class OperationCall(val invocation: Invocation, val approval: DoorApproval? = null)

class Operation<I, O>(
    val id: OperationId,
    val summary: String,
    val effect: SafetyClass,
    val input: KSerializer<I>,
    val output: KSerializer<O>,
    val schema: (() -> JsonObject)? = null,
    val effectFor: (JsonObject) -> SafetyClass = { effect },
    val appliesOwnPlan: suspend (JsonObject, CallCaller) -> Boolean = { _, _ -> false },
    val run: suspend OperationCall.(I) -> O,
) {
    val subject: String? get() = input.descriptor.subject()

    fun describe(provider: String) = OperationDescriptor(
        id, summary, effect, provider, schema?.invoke() ?: input.descriptor.jsonSchema(), output.descriptor.jsonSchema().takeIf { it.isNotEmpty() }, subject,
    )

    suspend fun execute(call: OperationCall, arguments: JsonObject, json: Json): JsonElement {
        val decoded = try { json.decodeFromJsonElement(input, arguments) } catch (invalid: IllegalArgumentException) { throw InvalidArguments(invalid) }
        return json.encodeToJsonElement(output, call.run(decoded))
    }
}

class InvalidArguments(cause: IllegalArgumentException) : IllegalArgumentException(cause.message, cause)

inline fun <reified I, reified O> operation(
    id: String,
    summary: String,
    effect: SafetyClass = SafetyClass.ReadOnly,
    noinline run: suspend OperationCall.(I) -> O,
) = Operation(OperationId(id), summary, effect, serializer<I>(), serializer<O>(), run = run)

fun rawOperation(
    id: String,
    summary: String,
    effect: SafetyClass,
    schema: () -> JsonObject,
    effectFor: (JsonObject) -> SafetyClass = { effect },
    run: suspend OperationCall.(JsonObject) -> JsonElement,
) = Operation(OperationId(id), summary, effect, JsonObject.serializer(), JsonElement.serializer(), schema, effectFor, run = run)

@Serializable
data class Invocation(
    val operation: OperationId,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val caller: CallCaller = CallCaller(),
    val requestId: String? = null,
)

@Serializable
sealed interface Outcome {
    @Serializable
    @SerialName("completed")
    data class Completed(val result: JsonElement) : Outcome

    @Serializable
    @SerialName("approval-required")
    data class ApprovalRequired(val approval: String, val effect: SafetyClass, val expiresAtEpochMillis: Long) : Outcome

    @Serializable
    @SerialName("denied")
    data class Denied(val approval: String, val by: String?) : Outcome

    @Serializable
    @SerialName("refused")
    data class Refused(val effect: SafetyClass, val reason: String) : Outcome

    @Serializable
    @SerialName("failed")
    data class Failed(val message: String) : Outcome
}

@Serializable
data class CatalogQuery(
    @Describe("Words that must all appear in the id or summary") val query: String? = null,
    @Describe("Only operations in this domain, the part of the id before the first dot") val domain: String? = null,
)

@Serializable
data class CatalogEntry(val id: OperationId, val summary: String, val effect: SafetyClass, val provider: String, val subject: String? = null, val prefer: OperationId? = null)

@Serializable
enum class Access { Allowed, Ask, Refused }

@Serializable
data class OperationAccess(val id: OperationId, val summary: String, val effect: SafetyClass, val provider: String, val seats: Map<String, Access>)

@Serializable
data class DescribeRequest(@Subject val operation: OperationId)

val OperationJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = false }
