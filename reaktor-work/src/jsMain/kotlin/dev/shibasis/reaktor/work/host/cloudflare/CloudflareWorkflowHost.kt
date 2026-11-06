package dev.shibasis.reaktor.work.host.cloudflare

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.work.Run
import dev.shibasis.reaktor.work.Work
import dev.shibasis.reaktor.work.definition.CallNode
import dev.shibasis.reaktor.work.definition.Durable
import dev.shibasis.reaktor.work.definition.EachNode
import dev.shibasis.reaktor.work.definition.LoopNode
import dev.shibasis.reaktor.work.definition.SignalNode
import dev.shibasis.reaktor.work.execution.Delivery
import dev.shibasis.reaktor.work.execution.RecordedSteps
import dev.shibasis.reaktor.work.execution.Replay
import dev.shibasis.reaktor.work.run.MaxPayloadBytes
import dev.shibasis.reaktor.work.run.RunLinks
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.promise
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8
import kotlin.js.Promise
import kotlin.time.Clock

class CloudflareWorkflowHost(private val work: Work, private val binding: dynamic, private val lifetime: CoroutineScope) {
    suspend fun <I : Any, O : Any> start(workflow: Durable<I, O>, input: I, key: String, links: RunLinks = RunLinks()): Run<O> {
        val plan = checkNotNull(work.plans()[workflow.workflowId]) { "Workflow '${workflow.workflowId}' is not part of this Work node's graph" }
        require(plan.nodes.none { it is CallNode<*, *> || it is EachNode<*, *> || it is LoopNode<*, *> }) {
            "Workflow '${plan.id}' runs child workflows, which Cloudflare Workflows instances do not run yet"
        }
        create(work.admit(workflow, input, key, links, awake = false))
        return work.run(workflow, key)
    }

    suspend fun <T : Any> signal(key: String, wait: SignalNode<T>, value: T, sender: AuthContext? = null) {
        work.authorizeSignal(wait, sender)
        val encoded = json.encodeToString(wait.serializer, value)
        require(encoded.encodeToByteArray().size <= MaxPayloadBytes) { "A signal is limited to 64 KB" }
        deliver(key, Delivery.Signal(wait.name, encoded))
    }

    suspend fun cancel(key: String) = deliver(key, Delivery.Cancel)

    fun run(event: dynamic, step: dynamic): Promise<String> = lifetime.promise {
        val start = json.decodeFromString(RunRecord.serializer(), event.payload as String)
        require(start.scope == work.scope) { "This instance belongs to another Work scope" }
        val plan = checkNotNull(work.plans()[start.workflow]) { "Workflow '${start.workflow}' is not installed in this host" }
        val record = Replay(plan, WorkflowSteps(step), Clock.System, ::mirror, start).run()
        val result = record.step(plan.workflow.output.name).result.takeIf { record.state == RunState.Succeeded }
        json.encodeToString(InstanceOutcome.serializer(), InstanceOutcome(record.state, result, record.reason))
    }

    private suspend fun create(record: RunRecord) {
        val id = instanceId(record.id)
        if (instance(id) != null) return
        val options: dynamic = js("({})")
        options.id = id
        options.params = json.encodeToString(RunRecord.serializer(), record)
        try {
            binding.create(options).unsafeCast<Promise<Any?>>().await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (instance(id) == null) throw failure
        }
    }

    private suspend fun deliver(key: String, delivery: Delivery) {
        val instance = checkNotNull(instance(instanceId(key))) { "Run '$key' has no Workflows instance" }
        val event: dynamic = js("({})")
        event.type = DeliveryEvent
        event.payload = json.encodeToString(Delivery.serializer(), delivery)
        instance.sendEvent(event).unsafeCast<Promise<Any?>>().await()
    }

    private suspend fun instance(id: String): dynamic = try {
        binding.get(id).unsafeCast<Promise<dynamic>>().await()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun mirror(record: RunRecord) {
        try {
            val visible = record.copy(nextWakeAtMillis = null)
            val stored = work.store.get(record.scope, record.id)
            when {
                stored == null -> work.store.admit(visible)
                record.revision > stored.revision -> work.store.replace(stored, visible.copy(revision = stored.revision + 1))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Logger.w("ReaktorWork") { "The D1 mirror of run '${record.id}' is behind: ${failure.message}" }
        }
    }

    private fun instanceId(key: String) = "run-" + "${work.scope.key}\u0000$key".encodeUtf8().sha256().hex().take(48)
}

@Serializable
data class InstanceOutcome(val state: RunState, val result: String?, val reason: String?)

private class WorkflowSteps(private val step: dynamic) : RecordedSteps {
    override suspend fun <T> record(name: String, serializer: KSerializer<T>, limitMillis: Long, produce: suspend () -> T): T = coroutineScope {
        val attempt = this
        val config: dynamic = js("({ retries: { limit: 0, delay: 0 } })")
        config.timeout = limitMillis.toDouble()
        val recorded = step.`do`(name, config) { _: dynamic -> attempt.promise { json.encodeToString(serializer, produce()) } }
            .unsafeCast<Promise<String>>().await()
        json.decodeFromString(serializer, recorded)
    }

    override suspend fun delivery(name: String, untilMillis: Long): Delivery? {
        val options: dynamic = js("({})")
        options.type = DeliveryEvent
        options.timeout = (untilMillis - currentMillis()).coerceAtLeast(ShortestWait).toDouble()
        return try {
            val event = step.waitForEvent(name, options).unsafeCast<Promise<dynamic>>().await()
            json.decodeFromString(Delivery.serializer(), event.payload as String)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (currentMillis() + ShortestWait >= untilMillis) null else throw failure
        }
    }

    private fun currentMillis() = Clock.System.now().toEpochMilliseconds()
}

private const val DeliveryEvent = "reaktor-work"
private const val ShortestWait = 1_000L
