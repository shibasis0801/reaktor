package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.db.core.ObjectState
import dev.shibasis.reaktor.db.core.ObjectStore
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortDelegate
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.js.JsExport
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@JsExport
data class ActorAddress(
    val graphId: String,
    val key: String,
) {
    override fun toString(): String = "$graphId/$key"
}

data class ActorKey<M : Any>(
    val name: String,
    val messageType: KClass<M>,
) {
    val nodeId: Uuid = stableActorUuid(name)
    val storeName: String = stableActorStoreName(name)
}

@JsExport.Ignore
inline fun<reified M : Any> actorKey(name: String): ActorKey<M> =
    ActorKey(name, M::class)

class Reply<R> internal constructor(
    private val deferred: kotlinx.coroutines.CompletableDeferred<R>,
) {
    fun complete(value: R): Boolean =
        deferred.complete(value)

    fun fail(error: Throwable): Boolean =
        deferred.completeExceptionally(error)
}

data class MailboxPolicy(
    val capacity: Int = 64,
    val overflow: BufferOverflow = BufferOverflow.SUSPEND,
)

sealed class ActorStatus {
    data object Created : ActorStatus()
    data object Starting : ActorStatus()
    data object Running : ActorStatus()
    data object Stopping : ActorStatus()
    data object Stopped : ActorStatus()
    data class Failed(val error: Throwable) : ActorStatus()
}

enum class ActorDirective {
    Resume,
    Restart,
    Stop,
    Escalate,
}

fun interface ActorSupervisorStrategy {
    suspend fun onActorFailure(
        actor: ActorNode<*>,
        message: Any,
        error: Throwable,
    ): ActorDirective
}

val DefaultActorSupervisorStrategy = ActorSupervisorStrategy { _, _, _ ->
    ActorDirective.Resume
}

interface ActorRef<M : Any> {
    val key: ActorKey<M>
    val address: ActorAddress
    val status: StateFlow<ActorStatus>

    suspend fun send(message: M)
    fun trySend(message: M): Boolean

    suspend fun<R> ask(
        timeout: Duration = 30.seconds,
        build: (Reply<R>) -> M,
    ): R
}

suspend inline fun<M : Any> ActorRef<M>.send(
    crossinline build: () -> M,
) {
    send(build())
}

suspend inline fun<M : Any, R> ActorRef<M>.askResult(
    timeout: Duration = 30.seconds,
    crossinline build: (Reply<Result<R>>) -> M,
): Result<R> =
    ask(timeout) { reply -> build(reply) }

class ActorTimerHandle internal constructor(
    private val job: Job,
) {
    fun cancel() {
        job.cancel()
    }
}

abstract class ActorNode<M : Any>(
    graph: Graph,
    final override val key: ActorKey<M>,
    private val mailboxPolicy: MailboxPolicy = MailboxPolicy(),
    dispatcher: CoroutineDispatcher = graph.coroutineDispatcher,
) : Node(
    graph = graph,
    dispatcher = dispatcher.limitedParallelism(1),
    id = key.nodeId,
    label = key.name,
), ActorRef<M> {
    final override val address: ActorAddress =
        ActorAddress(
            graphId = graph.id.toString(),
            key = key.name,
        )

    private data class Envelope<M : Any>(
        val message: M,
        val ask: kotlinx.coroutines.CompletableDeferred<*>? = null,
    )

    private val mailbox = Channel<Envelope<M>>(
        capacity = mailboxPolicy.capacity,
        onBufferOverflow = mailboxPolicy.overflow,
    )

    private var loopJob: Job? = null

    private val mutableStatus = MutableStateFlow<ActorStatus>(ActorStatus.Created)
    final override val status: StateFlow<ActorStatus> =
        mutableStatus.asStateFlow()

    protected val objectStore: ObjectStore by lazy {
        val database = Feature.Database
            ?: error("Feature.Database must be initialized before actor '${key.name}' opens state.")

        database.store(key.storeName)
    }

    init {
        registerProvider(
            key = Key(key.name),
            type = Type.create(this::class),
            impl = this,
        )
    }

    final override suspend fun send(message: M) {
        mailbox.send(Envelope(message))
    }

    final override fun trySend(message: M): Boolean =
        mailbox.trySend(Envelope(message)).isSuccess

    final override suspend fun<R> ask(
        timeout: Duration,
        build: (Reply<R>) -> M,
    ): R {
        val deferred = kotlinx.coroutines.CompletableDeferred<R>()
        mailbox.send(
            Envelope(
                message = build(Reply(deferred)),
                ask = deferred,
            ),
        )
        return withTimeout(timeout) {
            deferred.await()
        }
    }

    protected inline fun<reified S : Any> state(name: String = "state"): ObjectState<S> =
        objectStore.state(name)

    protected open suspend fun preStart() {}

    protected open suspend fun postStop() {}

    protected abstract suspend fun receive(message: M)

    protected open suspend fun onFailure(
        message: M,
        error: Throwable,
    ): ActorDirective =
        graph.actorSupervisorStrategy.onActorFailure(this, message, error)

    protected fun schedule(
        delay: Duration,
        message: M,
    ): ActorTimerHandle {
        val job = coroutineScope.launch {
            delay(delay)
            send(message)
        }
        return ActorTimerHandle(job)
    }

    protected fun every(
        interval: Duration,
        message: () -> M,
    ): ActorTimerHandle {
        val job = coroutineScope.launch {
            while (isActive) {
                delay(interval)
                send(message())
            }
        }
        return ActorTimerHandle(job)
    }

    override fun onTransition(
        previous: Lifecycle,
        next: Lifecycle,
    ) {
        when (next) {
            Lifecycle.Restoring,
            Lifecycle.Attaching -> startLoop()

            Lifecycle.Saving -> pauseLoop()

            Lifecycle.Destroying -> closeLoop()

            Lifecycle.Created -> Unit
        }
    }

    private fun startLoop() {
        if (loopJob != null) return

        loopJob = coroutineScope.launch {
            mutableStatus.value = ActorStatus.Starting

            try {
                preStart()
                mutableStatus.value = ActorStatus.Running

                for (envelope in mailbox) {
                    handle(envelope)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableStatus.value = ActorStatus.Failed(error)
                throw error
            } finally {
                if (mutableStatus.value !is ActorStatus.Failed) {
                    mutableStatus.value = ActorStatus.Stopping
                }

                try {
                    withContext(NonCancellable) {
                        postStop()
                    }
                } catch (error: Throwable) {
                    mutableStatus.value = ActorStatus.Failed(error)
                    throw error
                }

                if (mutableStatus.value !is ActorStatus.Failed) {
                    mutableStatus.value = ActorStatus.Stopped
                }
            }
        }
    }

    private suspend fun handle(envelope: Envelope<M>) {
        try {
            receive(envelope.message)
        } catch (error: Throwable) {
            envelope.ask?.completeExceptionally(error)

            when (onFailure(envelope.message, error)) {
                ActorDirective.Resume -> Unit
                ActorDirective.Restart -> restart()
                ActorDirective.Stop -> closeLoop()
                ActorDirective.Escalate -> throw error
            }
        }
    }

    private suspend fun restart() {
        mutableStatus.value = ActorStatus.Stopping
        postStop()
        mutableStatus.value = ActorStatus.Starting
        preStart()
        mutableStatus.value = ActorStatus.Running
    }

    private fun pauseLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun closeLoop() {
        mailbox.close()
        pauseLoop()
    }

    override fun close() {
        closeLoop()
        super.close()
    }
}

private fun stableActorStoreName(name: String): String =
    buildString {
        append("actors/")
        name.forEach { char ->
            append(
                if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-' || char == ':') {
                    char
                } else {
                    '_'
                }
            )
        }
    }

private fun stableActorUuid(name: String): Uuid {
    var first = -3750763034362895579L
    var second = 1099511628211L
    val input = "reaktor.actor:$name"

    input.forEach { char ->
        val code = char.code.toLong()
        first = (first xor code) * 1099511628211L
        second = (second xor (code + -7046029254386353131L)) * -4417276706812531889L
    }

    val raw = first.toHex16() + second.toHex16()
    val chars = raw.toCharArray()
    chars[12] = '5'
    chars[16] = "89ab"[chars[16].digitToInt(16) and 3]
    val hex = chars.concatToString()

    return Uuid.parse(
        "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    )
}

private fun Long.toHex16(): String =
    toULong().toString(16).padStart(16, '0')

@Suppress("UNCHECKED_CAST")
fun<M : Any> Graph.findActorNode(key: ActorKey<M>): ActorNode<M>? =
    node(key.nodeId)
        ?.also { node ->
            require(node is ActorNode<*>) {
                "Actor key '${key.name}' resolved to non-actor node ${node::class.simpleName}."
            }
            require(node.key == key) {
                "Actor key '${key.name}' resolved to actor key '${node.key.name}'."
            }
        } as? ActorNode<M>

inline fun<M : Any, reified A : ActorNode<M>> Graph.findActor(key: ActorKey<M>): A? {
    val actor = findActorNode(key) ?: return null
    require(actor is A) {
        "Actor '${key.name}' resolved to ${actor::class.simpleName}, expected ${A::class.simpleName}."
    }
    return actor
}

inline fun<M : Any, reified A : ActorNode<M>> Graph.provides(
    key: ActorKey<M>,
    create: (Graph) -> A,
): A {
    findActor<M, A>(key)?.let { return it }

    val actor = create(this)
    require(actor.key == key) {
        "Actor '${actor.key.name}' cannot be provided for key '${key.name}'."
    }
    attach(actor)
    return actor
}

inline fun<M : Any, reified A : ActorNode<M>> PortCapability.consumes(
    key: ActorKey<M>,
) = PropertyDelegateProvider<PortCapability, PortDelegate<ConsumerPort<A>>> { thisRef, _ ->
    val port = thisRef.registerConsumer<A>(
        key = Key(key.name),
        type = Type.create(A::class),
    )
    ReadOnlyProperty { _, _ -> port }
}
