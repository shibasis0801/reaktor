package dev.shibasis.reaktor.cloudflare

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.ChannelNode
import dev.shibasis.reaktor.graph.channels
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.service.contract.Call
import dev.shibasis.reaktor.service.contract.Channel
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.Link
import dev.shibasis.reaktor.service.contract.Retry
import dev.shibasis.reaktor.service.contract.Settlement
import dev.shibasis.reaktor.service.contract.Unavailable
import dev.shibasis.reaktor.service.contract.seal
import dev.shibasis.reaktor.service.contract.unseal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.math.ceil

class QueueLink(val binding: String, retry: Retry? = Retry()) : Link() {
    override val transport: String get() = "cloudflare-queue"

    init {
        retry?.let { intercept(it) }
    }

    override suspend fun <In, Out> transmit(operation: Call<In, Out>, exchange: Exchange<In, Out>): Out =
        throw UnsupportedOperationException("Queue $binding carries messages, not calls such as ${operation.id}")

    override suspend fun <M> deliver(channel: Channel<M>, exchange: Exchange<M, Unit>) {
        val queue: dynamic = exchange.cloudflareOrNull?.raw(binding) ?: CloudflareWorkers.env[binding] ?: error("Missing Cloudflare queue binding '$binding'")
        val body = JSON.parse<Any>(channel.seal(exchange.request, exchange.metadata).toString())
        try {
            queue.send(body, js("({ contentType: 'json' })")).unsafeCast<Promise<Any?>>().await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            throw Unavailable("Queue $binding did not accept a message for ${channel.id}", cause = failure)
        }
    }
}

fun queueLink(binding: String, retry: Retry? = Retry()): QueueLink = QueueLink(binding, retry)

@OptIn(DelicateCoroutinesApi::class)
fun Graph.queue(batch: Any, env: Any, executionContext: Any): Promise<Unit> = GlobalScope.promise {
    val context = cloudflareContext(env, executionContext)
    val name = batch.asDynamic().queue.unsafeCast<String>()
    val node = channels().firstOrNull { it.channel.id == name }
        ?: channels().singleOrNull { it.broker.subscribers.isNotEmpty() }
        ?: error("Graph '$label' has no channel for queue '$name'")
    batch.asDynamic().messages.unsafeCast<Array<dynamic>>().forEach { message -> node.settle(message, context) }
}

private suspend fun <M> ChannelNode<M>.settle(message: dynamic, context: CloudflareContext) {
    val attempt = (message.attempts as? Number)?.toInt() ?: 1
    val settlement = try {
        val envelope = channel.unseal(json.parseToJsonElement(JSON.stringify(message.body)), attempt)
        broker.receive(envelope, mapOf(CloudflareContextAttribute to context))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        Logger.e(failure) { "${channel.id} could not read message ${message.id} on attempt $attempt" }
        if (attempt >= channel.maxAttempts) Settlement.Ack else Settlement.Retry(broker.backoff(attempt))
    }
    when (settlement) {
        Settlement.Ack -> message.ack()
        is Settlement.Retry -> {
            val options: dynamic = js("({})")
            options.delaySeconds = ceil(settlement.after.inWholeMilliseconds / 1000.0).toInt()
            message.retry(options)
        }
    }
}
