package dev.shibasis.reaktor.cloud

import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.ProcessDefinitionSeal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the one-shot hand-off between [CloudToolProvider.run] and [CloudToolProvider.events].
 *
 * A run can have exactly one event-stream owner. This matters because the underlying process flow
 * is intentionally cold: collecting it twice must never launch the same cloud mutation twice, and
 * a rejected second collector must never cancel the first collector's process. Entries are removed
 * after success, failure, blocking, or cancellation so completed commands do not accumulate in the
 * provider.
 */
internal class CloudProcessRunRegistry(
    private val workingDirectory: File,
    private val runner: ProcessToolRunner,
) {
    private val runs = ConcurrentHashMap<String, PendingRun>()

    fun register(
        runId: String,
        command: List<String>,
        safety: SafetyClass,
        approval: CloudExecutionApproval?,
        environment: Map<String, String> = emptyMap(),
        sensitiveEnvironmentKeys: Set<String> = emptySet(),
        definitionDigest: String = "",
        definitionSeal: ProcessDefinitionSeal? = null,
    ) {
        purgeAbandoned()
        check(runs.size < MAX_PENDING_RUNS) {
            "Cloud run registry is full; consume or abandon an existing run before planning another"
        }
        val pending = PendingRun(
            command, safety, approval, environment.toMap(), sensitiveEnvironmentKeys.toSet(), definitionDigest,
            definitionSeal,
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        check(runs.putIfAbsent(runId, pending) == null) { "Cloud run '$runId' is already registered" }
    }

    fun events(runId: String): Flow<CloudEvent> {
        purgeAbandoned()
        val pending = runs[runId] ?: return emptyFlow()
        return flow {
            if (!pending.claimed.compareAndSet(false, true)) {
                emit(CloudEvent.Progress(runId, "event stream already claimed; command was not started again"))
                emit(CloudEvent.Completed(runId, DUPLICATE_STREAM_EXIT_CODE))
                return@flow
            }

            try {
                if (pending.safety.requiresApproval && pending.approval == null) {
                    emit(
                        CloudEvent.Progress(
                            runId,
                            "blocked: ${pending.safety.name} requires explicit operator approval",
                        ),
                    )
                    emit(CloudEvent.Completed(runId, APPROVAL_REQUIRED_EXIT_CODE))
                } else if (pending.definitionSeal?.let { it.currentDigest() != it.digest } == true) {
                    emit(CloudEvent.Progress(runId, "blocked: cloud program definition changed after review"))
                    emit(CloudEvent.Completed(runId, DEFINITION_DRIFT_EXIT_CODE))
                } else {
                    emitAll(
                        runner.run(
                            command = pending.command,
                            workingDir = workingDirectory,
                            runId = runId,
                            safety = pending.safety,
                            approval = pending.approval,
                            environment = pending.environment,
                            sensitiveEnvironmentKeys = pending.sensitiveEnvironmentKeys,
                            definitionDigest = pending.definitionDigest,
                            definitionSeal = pending.definitionSeal,
                        ),
                    )
                }
            } finally {
                runs.remove(runId, pending)
            }
        }
    }

    private fun purgeAbandoned(now: Long = System.currentTimeMillis()) {
        runs.entries.forEach { (runId, pending) ->
            if (!pending.claimed.get() && now - pending.createdAtEpochMillis >= ABANDONED_RUN_MILLIS) {
                runs.remove(runId, pending)
            }
        }
    }

    private data class PendingRun(
        val command: List<String>,
        val safety: SafetyClass,
        val approval: CloudExecutionApproval?,
        val environment: Map<String, String>,
        val sensitiveEnvironmentKeys: Set<String>,
        val definitionDigest: String,
        val definitionSeal: ProcessDefinitionSeal?,
        val createdAtEpochMillis: Long,
        val claimed: AtomicBoolean = AtomicBoolean(false),
    )

    private companion object {
        const val DUPLICATE_STREAM_EXIT_CODE = 125
        const val APPROVAL_REQUIRED_EXIT_CODE = 126
        const val DEFINITION_DRIFT_EXIT_CODE = 127
        const val MAX_PENDING_RUNS = 256
        const val ABANDONED_RUN_MILLIS = 10 * 60 * 1_000L
    }
}
