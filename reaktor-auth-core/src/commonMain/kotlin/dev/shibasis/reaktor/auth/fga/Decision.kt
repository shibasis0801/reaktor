@file:OptIn(ExperimentalAtomicApi::class)

package dev.shibasis.reaktor.auth.fga

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class AccessMode {
    @SerialName("shadow") Shadow,
    @SerialName("enforce") Enforce,
}

@Serializable
data class AccessDecision(
    val subject: String,
    val relation: String,
    @SerialName("object") val target: String,
    val allowed: Boolean,
    val mode: AccessMode,
    val rule: String,
    val facts: List<String> = emptyList(),
    val model: String = "",
    val tenant: String? = null,
    val trace: String? = null,
    val span: String? = null,
    @SerialName("at_ms") val atMillis: Long = 0,
    val contextual: List<String> = emptyList(),
) {
    val blocks: Boolean get() = !allowed && mode == AccessMode.Enforce

    val verdict: String get() = when {
        allowed -> "allowed"
        blocks -> "denied"
        else -> "would deny"
    }

    fun encode(): String = DecisionJson.encodeToString(serializer(), this)

    fun encodeForSpan(): String = copy(trace = null, span = null, atMillis = 0).encode()

    companion object {
        fun decode(text: String): AccessDecision? = runCatching { DecisionJson.decodeFromString(serializer(), text) }.getOrNull()
    }
}

fun interface AccessDecisionSink {
    fun record(decision: AccessDecision)
}

object AccessDecisions : AccessDecisionSink {
    const val Kept = 256

    private val listeners = AtomicReference<List<AccessDecisionSink>>(emptyList())
    private val recent = AtomicReference<List<AccessDecision>>(emptyList())

    val latest: List<AccessDecision> get() = recent.load()

    fun listen(sink: AccessDecisionSink): () -> Unit {
        listeners.change { it + sink }
        return { listeners.change { current -> current - sink } }
    }

    override fun record(decision: AccessDecision) {
        recent.change { (it + decision).takeLast(Kept) }
        listeners.load().forEach { runCatching { it.record(decision) } }
    }

    fun clear() {
        recent.store(emptyList())
    }
}

object FgaDigest {
    fun of(model: AuthorizationModel, tuples: Iterable<RelationTuple>): String =
        of(listOf(FgaDsl.print(model)) + tuples.map(RelationTuple::toString).sorted())

    fun of(parts: List<String>): String {
        var hash = -3750763034362895579L
        parts.forEach { part ->
            (part + "\n").forEach { char ->
                hash = hash xor char.code.toLong()
                hash *= 1099511628211L
            }
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}

private inline fun <T> AtomicReference<T>.change(transform: (T) -> T) {
    while (true) {
        val current = load()
        if (compareAndSet(current, transform(current))) return
    }
}

private val DecisionJson = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
}
