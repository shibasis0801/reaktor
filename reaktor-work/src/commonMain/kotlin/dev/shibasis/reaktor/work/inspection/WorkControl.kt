package dev.shibasis.reaktor.work.inspection

import dev.shibasis.reaktor.work.Work
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
enum class WorkControlAction { CANCEL, RETRY, RECONCILE }

@Serializable
data class WorkControlCommand(
    val id: String,
    val source: WorkSource,
    val runId: String,
    val expectedRevision: Long,
    val action: WorkControlAction,
    val step: String? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 256 && runId.isNotBlank() && runId.length <= 1024 && expectedRevision > 0)
        require(action != WorkControlAction.RECONCILE || step != null) { "Reconcile names the step it reconciles" }
    }
}

@Serializable
data class WorkControlReceipt(
    val commandId: String,
    val source: WorkSource,
    val runId: String,
    val expectedRevision: Long,
    val action: WorkControlAction,
    val committed: Boolean,
    val run: RunMetadata?,
    val observedAtMillis: Long,
    val schedulerArmed: Boolean,
)

fun interface WorkControlEndpoint {
    suspend fun execute(command: WorkControlCommand): WorkControlReceipt
}

class WorkController(
    private val work: Work,
    private val inspector: WorkInspector,
    private val clock: Clock = Clock.System,
) : WorkControlEndpoint {
    override suspend fun execute(command: WorkControlCommand): WorkControlReceipt {
        require(command.source == inspector.source) { "Work command host activation changed" }
        inspector.authorize("work.control.${command.action.name.lowercase()}")
        val record = when (command.action) {
            WorkControlAction.CANCEL -> work.cancel(command.runId, command.expectedRevision)
            WorkControlAction.RETRY -> work.retry(command.runId, command.expectedRevision, command.step)
            WorkControlAction.RECONCILE -> work.reconcile(command.runId, command.expectedRevision, checkNotNull(command.step))
        }
        val armed = record != null && try {
            work.rearm()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            false
        }
        return WorkControlReceipt(command.id, command.source, command.runId, command.expectedRevision, command.action,
            record != null, record?.metadata(), clock.now().toEpochMilliseconds(), armed)
    }
}
