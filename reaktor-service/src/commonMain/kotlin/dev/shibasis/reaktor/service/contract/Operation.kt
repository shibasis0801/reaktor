package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.HttpMethod
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlin.time.Duration

enum class Family { Call, Stream, Publish, Deliver }

sealed class Operation<In, Out>(
    val service: Service,
    val name: String,
    val request: KSerializer<In>,
    val response: KSerializer<Out>,
    val route: Route?,
    val effect: Effect,
    val idempotency: Idempotency,
) {
    abstract val family: Family

    val id: String get() = "${service.id}/$name"

    val binding: Route get() = route ?: Route(HttpMethod.POST, "/${service.id}/$name")

    val retriable: Boolean get() = effect.retriable || idempotency != Idempotency.None || binding.method in Idempotent

    override fun toString(): String = "$family($id)"

    private companion object {
        val Idempotent = setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.OPTIONS)
    }
}

class Call<In, Out> @PublishedApi internal constructor(
    service: Service,
    name: String,
    request: KSerializer<In>,
    response: KSerializer<Out>,
    route: Route?,
    effect: Effect,
    idempotency: Idempotency,
    val deadline: Duration?,
    val status: Int,
    private val handler: (suspend (In) -> Out)?,
) : Operation<In, Out>(service, name, request, response, route, effect, idempotency) {
    override val family: Family get() = Family.Call

    suspend operator fun invoke(request: In): Out {
        val answer = handler ?: return service.link.call(this, request)
        return answer(request)
    }

    internal fun answering(block: suspend (In) -> Out) =
        Call(service, name, request, response, route, effect, idempotency, deadline, status, block)
}

suspend operator fun <Out> Call<Unit, Out>.invoke(): Out = invoke(Unit)

class Stream<In, Out> @PublishedApi internal constructor(
    service: Service,
    name: String,
    request: KSerializer<In>,
    element: KSerializer<Out>,
    route: Route?,
    effect: Effect,
    private val handler: ((In) -> Flow<Out>)?,
) : Operation<In, Out>(service, name, request, element, route, effect, Idempotency.Natural) {
    override val family: Family get() = Family.Stream

    operator fun invoke(request: In): Flow<Out> = handler?.invoke(request) ?: service.link.stream(this, request)

    internal fun answering(block: (In) -> Flow<Out>) = Stream(service, name, request, response, route, effect, block)
}

operator fun <Out> Stream<Unit, Out>.invoke(): Flow<Out> = invoke(Unit)

data class Route(val method: HttpMethod, val path: String) {
    val parameters: List<String> get() = Parameter.findAll(path).map { it.groupValues[1] }.toList()

    private val pattern: Regex by lazy {
        Regex("^" + path.trimEnd('/').split(Parameter).joinToString("([^/]+)") { Regex.escape(it) } + "/?$")
    }

    fun match(method: HttpMethod, candidate: String): Map<String, String>? {
        if (method != this.method) return null
        val found = pattern.matchEntire(candidate.substringBefore('?')) ?: return null
        return parameters.zip(found.groupValues.drop(1).map { it.decodeURLPart() }).toMap()
    }

    private companion object {
        val Parameter = Regex("\\{([^}/]+)\\}")
    }
}

enum class Effect {
    ReadOnly,
    LiveRead,
    NonProductionWrite,
    ProductionReversibleWrite,
    CredentialWrite,
    DataMigration,
    Destructive,
    UnknownRemoteEffect;

    val retriable: Boolean get() = this <= LiveRead
}

enum class Idempotency { None, Natural, Keyed }
