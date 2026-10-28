package dev.shibasis.reaktor.tooling.runs

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.operation.Describe
import dev.shibasis.reaktor.tooling.operation.Subject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.seconds

@Serializable
enum class OperationalRunStatus {
    Planned,
    AwaitingApproval,
    Queued,
    Running,
    Succeeded,
    Failed,
    Cancelled,
    Blocked,
    Unknown,
}

@Serializable
data class RunQuery(
    val limit: Int = 20,
    @Describe("Only runs in this state") val status: OperationalRunStatus? = null,
)

@Serializable
data class RunRef(@Subject @Describe("Run id from run.list") val run: String)

@Serializable
data class RunWait(@Subject @Describe("Run id from run.list") val run: String, @Describe("Up to 25; call run.wait again while the run is unfinished") val timeoutSeconds: Int = 25)

@Serializable
data class RunLogs(
    @Subject @Describe("Run id from run.list") val run: String,
    @Describe("Only lines after this sequence number") val after: Long = -1,
    val limit: Int = 200,
)

open class RunsApi : Service("reaktor.run") {
    open val list by call<RunQuery, List<JsonObject>>(effect = Effect.ReadOnly, summary = "Recent runs, newest first, with their state.")
    open val show by call<RunRef, JsonObject>(effect = Effect.ReadOnly, summary = "One run with its outcome, artifacts and output.")
    open val logs by call<RunLogs, List<JsonElement>>(effect = Effect.ReadOnly, summary = "A run's output lines after a sequence number.")
    open val wait by call<RunWait, JsonObject>(effect = Effect.ReadOnly, deadline = 30.seconds, summary = "Waits up to 25 seconds for a run to finish and returns it; call it again while the run is unfinished. One wait runs at a time, and a second returns the run at once.")
    open val cancel by call<RunRef, Boolean>(effect = Effect.LocalEphemeral, summary = "Asks a running operation to stop; run.show reports when it has.")
}
