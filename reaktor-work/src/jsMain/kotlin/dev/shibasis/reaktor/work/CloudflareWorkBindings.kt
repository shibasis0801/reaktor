package dev.shibasis.reaktor.work

import kotlinx.coroutines.await
import kotlinx.coroutines.CancellationException
import kotlin.js.Promise

external interface WorkflowsBinding {
    fun create(options: WorkflowCreateOptions): Promise<WorkflowInstanceBinding>
    fun get(id: String): Promise<WorkflowInstanceBinding>
}
external interface WorkflowCreateOptions { var id: String; var params: String }
external interface WorkflowInstanceBinding { val id: String; fun status(): Promise<WorkflowStatus> }
external interface WorkflowStatus { val status: String; val output: dynamic }

/** Bindings belong to the current Worker invocation. Workflows owns all execution after handoff. */
class CloudflareWorkflowWork(private val bindingName: String, private val binding: WorkflowsBinding) {
    suspend fun handoff(context: WorkContext): WorkResult.HandedOff {
        val identity = "$bindingName:${context.effectKey}"
        val encoded: dynamic = js("new TextEncoder()").encode(identity)
        val buffer = (js("globalThis.crypto.subtle").digest("SHA-256", encoded) as Promise<dynamic>).await()
        val bytes: dynamic = js("new Uint8Array(buffer)")
        val id = (0 until (bytes.length as Int)).joinToString("") { (bytes[it] as Int).toString(16).padStart(2, '0') }
        val options = js("({})").unsafeCast<WorkflowCreateOptions>()
        options.id = id
        options.params = dev.shibasis.reaktor.core.framework.json.encodeToString(WorkIntent.serializer(), context.work.record.intent)
        try { binding.create(options).await() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failedCreate: Throwable) {
            // Covers interruption after successful creation. Existence must be confirmed, not inferred from an error message.
            if (binding.get(id).await().status().await().status == "unknown") throw failedCreate
        }
        return WorkResult.HandedOff(WorkHandoff("cloudflare-workflows", bindingName, id))
    }

    suspend fun reconcile(runtime: WorkRuntime, definition: WorkDefinition<*>) {
        runtime.store.list(runtime.scope).filter {
            it.state == WorkState.HANDED_OFF && it.intent.definition == definition.id &&
                it.handoff?.profile == "cloudflare-workflows" && it.handoff.provider == bindingName
        }.forEach { record ->
            val handoff = checkNotNull(record.handoff)
            val status = binding.get(handoff.id).await().status().await()
            val result = when (status.status) {
                "complete" -> {
                    val receipt: String? = js("JSON.stringify(status.output)") as String?
                    if (receipt.isNullOrBlank() || receipt.encodeToByteArray().size > 65_536)
                        WorkResult.Unknown("Workflow completed without a bounded domain receipt")
                    else WorkResult.Success(receipt)
                }
                "errored", "terminated" -> WorkResult.Unknown("Workflow stopped; reconcile accepted external effects")
                else -> null
            }
            if (result != null) runtime.reconcileHandoff(record.intent.id, definition, handoff, result)
        }
    }
}

external interface WorkQueueBinding { fun send(body: String, options: QueueWakeOptions): Promise<Unit> }
external interface QueueWakeOptions { var delaySeconds: Int; var contentType: String }
external interface QueueWakeMessage { val body: String; fun ack(); fun retry() }

/** Queue messages wake a scoped authority. They do not carry an executable handler or a task payload. */
class CloudflareQueueWorkScheduler(private val queue: WorkQueueBinding) : WorkScheduler {
    override suspend fun arm(scope: WorkScope, nextWakeAtMillis: Long?) {
        if (nextWakeAtMillis == null) return
        val options = js("({})").unsafeCast<QueueWakeOptions>()
        options.contentType = "text"
        options.delaySeconds = ((nextWakeAtMillis - (js("Date.now()") as Double).toLong()) / 1000).coerceIn(0, 86_400).toInt()
        queue.send(dev.shibasis.reaktor.core.framework.json.encodeToString(WorkScope.serializer(), scope), options).await()
    }
}

suspend fun consumeCloudflareWorkWake(message: QueueWakeMessage, host: WorkHost) {
    val scope = dev.shibasis.reaktor.core.framework.json.decodeFromString(WorkScope.serializer(), message.body)
    val session = host.open(scope)
    try { session.runtime.drain(); message.ack() }
    catch (failure: Throwable) { message.retry(); throw failure }
    finally { session.close() }
}
