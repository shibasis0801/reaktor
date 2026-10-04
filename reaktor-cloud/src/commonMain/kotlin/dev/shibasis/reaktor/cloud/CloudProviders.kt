package dev.shibasis.reaktor.cloud

import kotlinx.coroutines.flow.Flow

/** A tool provider that exposes runnable operations with streamed events (Dagger, Pulumi, …). */
interface CloudToolProvider {
    val id: String
    suspend fun operations(): List<CloudOperation>
    /** Prepare the immutable command fingerprint that an operator must review before approval. */
    suspend fun plan(command: CloudCommand): CloudExecutionPlan
    /**
     * Plans a run. Mutating commands remain blocked unless [approval] is explicitly supplied; the
     * default preserves the legacy read-only call path without silently approving writes.
     */
    suspend fun run(
        command: CloudCommand,
        approval: CloudExecutionApproval? = null,
    ): CloudRun
    fun events(runId: String): Flow<CloudEvent>
}
