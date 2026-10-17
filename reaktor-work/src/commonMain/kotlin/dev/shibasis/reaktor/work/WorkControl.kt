package dev.shibasis.reaktor.work

import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
enum class WorkControlAction { CANCEL, RETRY, RECONCILE }

@Serializable
data class WorkControlCommand(val id: String, val source: WorkSource, val workId: String,
    val expectedRevision: Long, val action: WorkControlAction) {
    init { require(id.isNotBlank() && id.length <= 256 && workId.isNotBlank() && workId.length <= 1024 && expectedRevision > 0) }
}

@Serializable
data class WorkControlReceipt(val commandId: String, val source: WorkSource, val workId: String,
    val expectedRevision: Long, val action: WorkControlAction, val committed: Boolean,
    val record: WorkMetadata?, val observedAtMillis: Long, val schedulerArmed: Boolean)

fun interface WorkControlEndpoint { suspend fun execute(command: WorkControlCommand): WorkControlReceipt }

class WorkController(
    private val runtime: WorkRuntime, private val inspector: WorkInspector,
    private val reconcileProvider: suspend (WorkRecord) -> WorkResult? = { null },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : WorkControlEndpoint {
    override suspend fun execute(command: WorkControlCommand): WorkControlReceipt {
        require(command.source == inspector.source) { "Work command host activation changed" }
        inspector.authorize("work.control.${command.action.name.lowercase()}")
        val before = runtime.store.get(runtime.scope, command.workId) ?: error("Work record unavailable")
        require(before.revision == command.expectedRevision) { "Work command revision changed" }
        val definition = runtime.definition(before.intent.definition)?.impl ?: error("Work definition is not installed")
        runtime.validateAdmission(definition)
        require(before.intent.definitionVersion == definition.version && before.intent.payloadSchema == definition.payloadSchema) { "Work definition revision changed" }
        if (command.action == WorkControlAction.RETRY) {
            val policy = definition.operatorRetry ?: error("Definition does not authorize operator retry")
            runtime.validateOperatorRetry(policy)
        }
        val result = if (command.action == WorkControlAction.RECONCILE) reconcileProvider(before) ?: error("Provider reconciliation unavailable") else null
        val record = runtime.store.control(runtime.scope, command.workId, command.expectedRevision, now(), command.action, result)
        val armed = if (record == null) false else try { runtime.rearm(); true }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        return WorkControlReceipt(command.id, command.source, command.workId, command.expectedRevision,
            command.action, record != null, record, now(), armed)
    }
}
