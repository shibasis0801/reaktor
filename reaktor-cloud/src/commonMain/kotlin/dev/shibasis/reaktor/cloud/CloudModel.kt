package dev.shibasis.reaktor.cloud

/**
 * Provider-neutral cloud model — the substrate the reaktorDesktop Cloud pane renders and the
 * workbench/agents consume. See the design at reaktor.build/docs/reaktor-cloud-pane.
 *
 * One [CloudResource] shape every provider maps onto, one [CloudOperation] shape every tool maps
 * onto, and [CloudRun]/[CloudEvent] for streamed tool execution. (Kept plain for now; add
 * kotlinx.serialization annotations when transport/persistence needs them.)
 */

enum class ResourceStatus { Healthy, Degraded, Down, Unknown }

enum class RunStatus { Pending, Running, Succeeded, Failed, Cancelled }

/** A single cloud resource (Worker, Durable Object, D1, R2, Pod, PgTable, …) from any provider. */
data class CloudResource(
    val provider: String,            // "cloudflare" | "supabase" | "gcp" | "k3s" | "memgraph" | "grafana"
    val kind: String,                // "Worker" | "DurableObject" | "D1" | "R2" | "Pod" | "PgTable" | ...
    val id: String,
    val name: String,
    val status: ResourceStatus,
    val region: String? = null,
    val metrics: Map<String, String> = emptyMap(),   // "req/s" -> "1.2k", "size" -> "4.2 GB"
    val consoleUrl: String? = null,                   // provider dashboard deep-link (open in Chrome)
    val grafanaUrl: String? = null,                   // the right Grafana dashboard, if any
    val tags: Map<String, String> = emptyMap(),
)

/** Rollup health for a provider section in the pane. */
data class ProviderHealth(
    val provider: String,
    val status: ResourceStatus,
    val detail: String? = null,
)

/** A declared input for an operation (e.g. the stack to target, the pod to restart). */
data class CloudInput(
    val key: String,
    val label: String,
    val required: Boolean = false,
    val default: String? = null,
)

/** An executable operation a tool exposes (dagger.pr, pulumi.preview, k3s.restartPod, …). */
data class CloudOperation(
    val provider: String,            // "dagger" | "pulumi" | "wrangler" | "k3s" | ...
    val id: String,                  // "dagger.pr" | "pulumi.preview" | "k3s.restartPod"
    val label: String,
    val destructive: Boolean = false,
    val inputs: List<CloudInput> = emptyList(),
)

/** A concrete invocation of an operation. */
data class CloudCommand(
    val operationId: String,
    val provider: String,
    val fn: String,                  // the tool function/verb: "pr", "preview", "up", ...
    val args: Map<String, String> = emptyMap(),
    val stack: String? = null,
)

/** A handle to a running (or finished) operation; [events] streams its progress. */
data class CloudRun(
    val id: String,
    val operationId: String,
    val status: RunStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

/** A streamed event from a [CloudRun] — logs, progress, completion. */
sealed interface CloudEvent {
    val runId: String
    data class Log(override val runId: String, val line: String) : CloudEvent
    data class Progress(override val runId: String, val message: String, val percent: Double? = null) : CloudEvent
    data class Completed(override val runId: String, val exitCode: Int) : CloudEvent
}
