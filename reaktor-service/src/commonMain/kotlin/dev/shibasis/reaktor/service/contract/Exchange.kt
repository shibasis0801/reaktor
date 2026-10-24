package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Instant

enum class Side {
    Client,
    Server;

    val phase: ServiceExecutionPhase get() = if (this == Client) ServiceExecutionPhase.CLIENT else ServiceExecutionPhase.SERVER

    companion object {
        val Both: Set<Side> = entries.toSet()
    }
}

class Exchange<In, R> internal constructor(
    val family: Family,
    val side: Side,
    val contract: String,
    val name: String,
    val operation: Operation<In, *>?,
    val channel: Channel<In>?,
    request: In,
    val metadata: Metadata,
    val attributes: MutableMap<String, Any?>,
    val call: ServiceCall,
    var deadline: Instant?,
) {
    var request: In = request

    var attempt: Int = 1

    val effect: Effect get() = operation?.effect ?: if (family == Family.Deliver) Effect.ProductionReversibleWrite else Effect.UnknownRemoteEffect

    val retriable: Boolean get() = operation?.retriable ?: (channel?.delivery == Delivery.AtLeastOnce)

    val remaining: Duration? get() = deadline?.let { (it - Clock.System.now()).coerceAtLeast(ZERO) }

    val expired: Boolean get() = remaining == ZERO

    val subject: String get() = "$contract/$name"

    override fun toString(): String = "Exchange($family $side $subject attempt=$attempt)"
}

class Metadata(initial: Map<String, String> = emptyMap()) : Iterable<Map.Entry<String, String>> {
    private val values = LinkedHashMap<String, Pair<String, String>>()

    init {
        initial.forEach { (name, value) -> set(name, value) }
    }

    operator fun get(name: String): String? = values[name.lowercase()]?.second

    operator fun set(name: String, value: String) {
        values[name.lowercase()] = name to value
    }

    operator fun contains(name: String): Boolean = name.lowercase() in values

    fun remove(name: String): String? = values.remove(name.lowercase())?.second

    fun toMap(): Map<String, String> = values.values.toMap()

    fun copy(): Metadata = Metadata(toMap())

    override fun iterator(): Iterator<Map.Entry<String, String>> = toMap().entries.iterator()

    override fun toString(): String = values.values.map { it.first }.toString()

    companion object {
        const val Traceparent = "traceparent"
        const val Baggage = "baggage"
        const val Deadline = "reaktor-deadline"
        const val Contract = "reaktor-contract"
        const val IdempotencyKey = "idempotency-key"
        const val Environment = "X-Environment"
        const val RetryAfter = "retry-after"

        val Sent: List<String> = listOf(Traceparent, Baggage, Environment, Contract, Deadline, IdempotencyKey)
    }
}

class ExchangeScope(val exchange: Exchange<*, *>) : AbstractCoroutineContextElement(ExchangeScope) {
    companion object Key : CoroutineContext.Key<ExchangeScope>
}

suspend fun exchangeOrNull(): Exchange<*, *>? = currentCoroutineContext()[ExchangeScope]?.exchange

suspend fun exchange(): Exchange<*, *> = exchangeOrNull() ?: error("This code is not running inside a reaktor service exchange")
