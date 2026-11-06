package dev.shibasis.reaktor.work.execution

import dev.shibasis.reaktor.work.definition.StepNode
import dev.shibasis.reaktor.work.definition.WorkflowPlan
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.StepState
import dev.shibasis.reaktor.work.run.WorkScope
import dev.shibasis.reaktor.work.store.Admission
import dev.shibasis.reaktor.work.store.WorkStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

internal class RequestStore(private val durable: WorkStore, private val plan: WorkflowPlan, first: RunRecord) : WorkStore by durable {
    private val mutex = Mutex()

    @Volatile
    private var held: RunRecord? = first
    private var conflicted = false

    val stored: Boolean get() = held == null

    override suspend fun get(scope: WorkScope, id: String): RunRecord? =
        mutex.withLock { held?.takeIf { it.scope == scope && it.id == id } } ?: durable.get(scope, id)

    override suspend fun replace(before: RunRecord, after: RunRecord): Boolean {
        val replaced = mutex.withLock {
            val record = held?.takeIf { it.scope == before.scope && it.id == before.id } ?: return@withLock null
            if (record.revision != before.revision) return@withLock false
            val next = after.copy(transitions = record.transitions + after.transitions, changed = (record.changed + after.changed).distinct())
            if (writes(before, after)) settle(next) else true.also { held = next }
        }
        return replaced ?: durable.replace(before, after)
    }

    override suspend fun admit(record: RunRecord): Admission {
        mutex.withLock { held?.takeIf { it.scope == record.scope && it.id == record.parent }?.let { settle(it) } }
        return durable.admit(record)
    }

    suspend fun persist(): Boolean = mutex.withLock {
        held?.let { settle(it) }
        !conflicted
    }

    private suspend fun settle(record: RunRecord): Boolean {
        val admission = durable.admit(record)
        held = null
        conflicted = admission is Admission.Conflict
        return admission is Admission.Accepted && !admission.existing
    }

    private fun writes(before: RunRecord, after: RunRecord): Boolean = after.steps.any { (path, step) ->
        (step.state == StepState.Running || step.state == StepState.Undoing) &&
            before.step(path).let { it.state != step.state || it.fence != step.fence } &&
            (plan.node(path) as? StepNode<*>)?.effect?.retriable != true
    }
}
