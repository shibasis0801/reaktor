package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A reasoning-effort level in the provider's own vocabulary.
 *
 * Deliberately not an enum. Claude advertises `low, medium, high, xhigh, max` and Codex advertises
 * its own set per model; a shared enum would silently cap the user at whatever both happen to
 * support, which is the one thing this type exists to prevent.
 */
@Serializable
@JvmInline
value class NativeEffort(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 32) { "Effort must be a short provider token" }
    }
}

/**
 * What a provider accepts, as observed rather than assumed.
 *
 * [supported] is null when the installed CLI gives no way to enumerate the set — `codex exec` has
 * no effort flag and `codex models` needs a terminal — and an unknown set is not an empty one:
 * the value is passed through and the outcome recorded, instead of being refused on a guess.
 */
@Serializable
data class EffortSupport(
    val supported: List<NativeEffort>? = null,
    val default: NativeEffort? = null,
    val source: String,
) {
    fun accepts(effort: NativeEffort): Boolean = supported?.contains(effort) ?: true
}

/**
 * The four independent facts a capability needs, because collapsing them into one flag is how a
 * schema entry turns into a shipped feature.
 *
 * A capability the adapter implements but no test covers is real and unproven, which is a
 * different state from one the installed version never advertised.
 */
@Serializable
data class Qualification(
    val advertised: Boolean = false,
    val configured: Boolean = false,
    val implemented: Boolean = false,
    val qualifiedBy: String? = null,
) {
    /** Usable now. Says nothing about whether anyone has checked that it behaves. */
    val usable: Boolean get() = advertised && configured && implemented

    /** Usable and unproven: offer it, label it, do not rely on it. */
    val experimental: Boolean get() = usable && qualifiedBy == null

    companion object {
        val unavailable = Qualification()

        fun qualified(by: String) = Qualification(advertised = true, configured = true, implemented = true, qualifiedBy = by)
    }
}

/**
 * How much of a model's reasoning this transport can show.
 *
 * The distinction is load-bearing and must survive the adapter: a provider-authored summary is not
 * the model's thinking, and neither may be presented as proof of why the model acted. Relabelling
 * one as the other is the failure this enum exists to make impossible.
 */
@Serializable
enum class ReasoningFidelity {
    /** The provider's own summary of its reasoning. */
    Summary,

    /** Thinking content the provider chose to surface verbatim. */
    Thinking,

    /** This transport or model exposes none. An ordinary state, never filled in by inference. */
    Unavailable,
}

/** What one runtime can do here, for this principal, on this machine. */
@Serializable
data class ProviderCapability(
    val runtime: RuntimeKind,
    val executable: String? = null,
    val version: String? = null,
    val effort: EffortSupport? = null,
    val effortControl: Qualification = Qualification.unavailable,
    val reasoning: ReasoningFidelity = ReasoningFidelity.Unavailable,
    val reasoningControl: Qualification = Qualification.unavailable,
    val notes: List<String> = emptyList(),
)

/**
 * Requested, resolved and observed kept apart.
 *
 * [observed] stays null when the provider reports no effective value. Copying [resolved] into it
 * would turn an assumption into evidence, and the ledger would then be unable to tell a granted
 * effort from an assumed one.
 */
@Serializable
data class EffortRecord(
    val requested: NativeEffort? = null,
    val resolved: NativeEffort? = null,
    val observed: NativeEffort? = null,
) {
    val unknownEffective: Boolean get() = observed == null

    companion object {
        val none = EffortRecord()
    }
}

/** The outcome of asking for an effort level, before anything is dispatched. */
sealed interface EffortResolution {
    /** Nothing was asked for; the provider's own default applies and is not recorded as ours. */
    data object ProviderDefault : EffortResolution

    data class Granted(val effort: NativeEffort) : EffortResolution

    /**
     * The provider advertises a set and this is not in it. An actionable error rather than a quiet
     * downgrade, because a silently cheaper turn is indistinguishable from a worse model.
     */
    data class Unsupported(val requested: NativeEffort, val supported: List<NativeEffort>) : EffortResolution {
        val message: String
            get() = "Effort '${requested.value}' is not supported. Available: " +
                supported.joinToString(", ") { it.value }
    }
}

/** Resolves a request against what the provider says it takes. */
fun EffortSupport?.resolve(requested: NativeEffort?): EffortResolution = when {
    requested == null -> EffortResolution.ProviderDefault
    this == null -> EffortResolution.Granted(requested)
    accepts(requested) -> EffortResolution.Granted(requested)
    else -> EffortResolution.Unsupported(requested, supported.orEmpty())
}

/** Thrown rather than downgraded, so the caller has to decide what to do about it. */
class UnsupportedEffortException(val resolution: EffortResolution.Unsupported) :
    IllegalArgumentException(resolution.message)
