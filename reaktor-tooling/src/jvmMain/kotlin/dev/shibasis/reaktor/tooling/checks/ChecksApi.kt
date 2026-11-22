package dev.shibasis.reaktor.tooling.checks

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.atlas.AtlasNext
import dev.shibasis.reaktor.tooling.operation.Describe
import dev.shibasis.reaktor.tooling.operation.Schema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

open class ChecksApi : Service("reaktor.check") {
    open val catalog by call<CheckCatalogRequest, JsonElement>(effect = Effect.ReadOnly, summary = "Checks a task can run to prove its work.")

    open val start by call<CheckStartRequest, JsonElement>(effect = Effect.LocalArtifactWrite, summary = "Starts a check for a task and retains its receipt.")

    open val linkEvidence by call<CheckLinkRequest, JsonElement>(name = "link", effect = Effect.LocalEphemeral, summary = "Links existing check evidence to a task.")

    open val result by call<CheckResultRequest, JsonElement>(effect = Effect.ReadOnly, summary = "Read one finished run as a typed result: outcome, failures with file and line, the candidate revision it belongs to, and how much of the output the parser understood. Returns the diagnostics rather than the log.")

    open val artifact by call<CheckArtifactRequest, JsonElement>(effect = Effect.ReadOnly, summary = "Read a bounded range of a retained non-sensitive check log.")

    open val run by call<CheckRunRequest, CheckRunAnswer>(effect = Effect.LocalArtifactWrite, deadline = kotlin.time.Duration.parse("1m"), summary = "Runs affected test classes through the code graph by default, excluding window tests; narrow:false explicitly runs whole checks. Missing safe class selection is reported without broadening. Waits up to 25 seconds and reports run receipts, selected classes, reused results, compiler errors, assertions with source lines and Gradle failure causes in a bounded summary. Queued admission follows the bound plan item's current priority and dependencies. Follow the returned next request while unfinished; waiting admissions continue with queue, admitted checks with run or runs.")
}

@Serializable
data class CheckCatalogRequest(
    @Describe("Owned task") @Schema(nullable = false) val taskId: String? = null,
    @Describe("Captured candidate owned by taskId") @Schema(nullable = false) val candidateId: String? = null,
)

@Serializable
data class CheckStartRequest(
    @Describe("Owned task") val taskId: String,
    @Describe("Named local kernel check") @Schema(nullable = false) val checkId: String? = null,
    @Describe("Exact captured candidate") @Schema(nullable = false) val candidateId: String? = null,
    @Schema(nullable = false, itemsDescription = "Declared candidate check") val checkIds: List<String>? = null,
    val rerun: Boolean = false,
    @Describe("Idempotency key for this exact check") @Schema(nullable = false) val requestId: String? = null,
)

@Serializable
data class CheckLinkRequest(
    @Describe("Reaktor conversation id") val taskId: String,
    @Describe("Background check run") val runId: String,
)

@Serializable
data class CheckResultRequest(
    @Describe("Exact run id from control_plane_snapshot") val runId: String,
)

@Serializable
data class CheckArtifactRequest(
    @Describe("Kernel run id") val runId: String,
    @Schema(minimum = 0) val offset: Long = 0,
    @Schema(minimum = 1, maximum = 100000) val limit: Int = 24000,
)

@Serializable
enum class CheckReach { Edited, Users, Flows }

@Serializable
enum class CheckEvidence { Ran, Reused, NotRun, NoResults }

@Serializable
data class CheckRunRequest(
    @Describe("A plan id from change_apply; the files it wrote choose the checks") val change: String? = null,
    @Describe("Workspace-relative files whose checks to run, when there is no change") val paths: List<String> = emptyList(),
    @Describe("A run id from an earlier check_run, to wait for it again instead of starting checks") val run: String? = null,
    @Describe("The exact run ids from a multi-check continuation; waits without starting checks") val runs: List<String> = emptyList(),
    @Describe("Edited runs the tests of the modules the files belong to; add Users for the modules that use them and Flows for the slow Hangar and window flows")
    val reach: List<CheckReach> = listOf(CheckReach.Edited),
    @Describe("Seconds to wait for the run, 0 to 25") val wait: Int = 25,
    @Describe("Run the tests even when Gradle would reuse their last result because nothing they read changed") val rerun: Boolean = false,
    @Describe("Default: run affected test classes through the code graph, excluding window tests. A test reached only through wiring or reflection is not run; false explicitly runs whole checks")
    val narrow: Boolean = true,
    @Describe("Canonical open plan leaf, plan:<thread>#<item>; queued checks follow its current effective priority") val planItem: String? = null,
    @Describe("Retained check admission id from an earlier check_run; continue it without submitting more checks") val queue: String? = null,
)

@Serializable
data class CheckReport(
    val node: String,
    val title: String,
    val status: String,
    val evidence: CheckEvidence? = null,
    val tests: Int? = null,
    val failed: Int? = null,
    val skipped: Int? = null,
    val resultsAt: Long? = null,
    val only: List<String> = emptyList(),
)

@Serializable
data class CheckFailed(val phase: String, val cause: String)

@Serializable
data class CheckRunAnswer(
    val run: String?,
    val status: String,
    val checks: List<CheckReport>,
    val diagnostics: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val next: List<AtlasNext> = emptyList(),
    val failures: List<CheckFailed> = emptyList(),
    val source: String? = null,
    val started: List<String> = emptyList(),
    val queue: String? = null,
)
