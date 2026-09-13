package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable

@Serializable
enum class CheckOutcome { Passed, Failed, Errored, Unknown }

/**
 * How much of the output the parser actually understood.
 *
 * The distinction is the whole point of reducing at all. A reduction that dropped a diagnostic it
 * did not recognise, and reported itself complete, is worse than no reduction: the agent would act
 * on a partial picture believing it was whole.
 */
@Serializable
sealed interface CheckCoverage {
    /** Every diagnostic line the parser looked for was recognised. */
    @Serializable
    data object Complete : CheckCoverage

    /** Recognised some of it. [unrecognisedLines] is what was left out of the summary. */
    @Serializable
    data class Partial(val unrecognisedLines: Int, val reason: String) : CheckCoverage

    /** No parser claimed this format. The excerpt is bounded and the artifact holds the rest. */
    @Serializable
    data class Unparsed(val reason: String) : CheckCoverage
}

/** One diagnostic, addressed well enough that a repair turn can go straight to it. */
@Serializable
data class CheckFailure(
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val kind: String? = null,
)

/** A pointer to material kept outside the context window. */
@Serializable
data class ArtifactRef(val id: String, val bytes: Long, val kind: String = "log")

/**
 * What a check produced, small enough to put in a prompt and specific enough to act on.
 *
 * A Gradle run prints thousands of lines to say three things. Reducing that before it reaches a
 * context window is the largest per-turn saving available here, and it costs no model tokens —
 * but a reduction is only as complete as its parser, so [coverage] travels with it and the full
 * output stays retrievable through [log] rather than being thrown away.
 *
 * [revision] binds the result to the candidate it describes. A check result without one cannot be
 * told apart from a stale result for different code, which is how a green run gets attributed to
 * a change it never saw.
 */
@Serializable
data class CheckResult(
    val command: List<String>,
    val outcome: CheckOutcome,
    val exitCode: Int? = null,
    val revision: String? = null,
    val environment: String? = null,
    val failures: List<CheckFailure> = emptyList(),
    val failureCount: Int = failures.size,
    val excerpt: String? = null,
    val log: ArtifactRef? = null,
    val parser: String = "none",
    val coverage: CheckCoverage = CheckCoverage.Unparsed("no parser ran"),
    val durationMillis: Long? = null,
) {
    init {
        require(command.isNotEmpty()) { "A check result names the command that produced it" }
        require(failureCount >= failures.size) { "Reported failures cannot exceed the count" }
    }

    /** True when this result can be attributed to a specific candidate. */
    val attributable: Boolean get() = revision != null
}

/**
 * Turns one tool's output into a [CheckResult].
 *
 * Separate from execution for the same reason the harness parsers are: build output belongs to
 * Gradle, to the Kotlin compiler, to JUnit, and the only way to know a reducer still matches is to
 * run it against captured output.
 */
fun interface CheckReducer {
    /** Returns null when this reducer does not recognise the output at all. */
    fun reduce(command: List<String>, exitCode: Int?, output: String): CheckResult?
}
