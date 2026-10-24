package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.core.framework.kSerializer
import dev.shibasis.reaktor.service.HttpMethod
import dev.shibasis.reaktor.service.ServiceAccess
import kotlinx.coroutines.flow.Flow
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration

abstract class Service(val id: String, val version: Int = 1) {
    private val declared = LinkedHashMap<String, Operation<*, *>>()
    private val answered = LinkedHashMap<String, Operation<*, *>>()
    private val declaredChannels = mutableListOf<Channel<*>>()

    var link: Link = Unlinked
        private set

    init {
        require(id.isNotBlank()) { "A service needs an id" }
    }

    open val access: ServiceAccess get() = ServiceAccess.Undeclared

    val operations: List<Operation<*, *>> get() = declared.values.toList()

    val channels: List<Channel<*>> get() = declaredChannels

    val answers: Set<String> get() = answered.keys

    fun operation(name: String): Operation<*, *>? = answered[name] ?: declared[name]

    val shape: ContractShape by lazy { ContractShape.of(this) }

    val fingerprint: String get() = shape.fingerprint

    protected inline fun <reified In, reified Out> get(
        path: String,
        effect: Effect = Effect.UnknownRemoteEffect,
        idempotency: Idempotency = Idempotency.None,
        deadline: Duration? = null,
        status: Int = 200,
        name: String? = null,
    ): Declares<Call<In, Out>> = call(Route(HttpMethod.GET, path), effect, idempotency, deadline, status, name)

    protected inline fun <reified In, reified Out> post(
        path: String,
        effect: Effect = Effect.UnknownRemoteEffect,
        idempotency: Idempotency = Idempotency.None,
        deadline: Duration? = null,
        status: Int = 200,
        name: String? = null,
    ): Declares<Call<In, Out>> = call(Route(HttpMethod.POST, path), effect, idempotency, deadline, status, name)

    protected inline fun <reified In, reified Out> put(
        path: String,
        effect: Effect = Effect.UnknownRemoteEffect,
        idempotency: Idempotency = Idempotency.None,
        deadline: Duration? = null,
        status: Int = 200,
        name: String? = null,
    ): Declares<Call<In, Out>> = call(Route(HttpMethod.PUT, path), effect, idempotency, deadline, status, name)

    protected inline fun <reified In, reified Out> patch(
        path: String,
        effect: Effect = Effect.UnknownRemoteEffect,
        idempotency: Idempotency = Idempotency.None,
        deadline: Duration? = null,
        status: Int = 200,
        name: String? = null,
    ): Declares<Call<In, Out>> = call(Route(HttpMethod.PATCH, path), effect, idempotency, deadline, status, name)

    protected inline fun <reified In, reified Out> delete(
        path: String,
        effect: Effect = Effect.UnknownRemoteEffect,
        idempotency: Idempotency = Idempotency.None,
        deadline: Duration? = null,
        status: Int = 200,
        name: String? = null,
    ): Declares<Call<In, Out>> = call(Route(HttpMethod.DELETE, path), effect, idempotency, deadline, status, name)

    protected inline fun <reified In, reified Out> call(
        route: Route? = null,
        effect: Effect = Effect.UnknownRemoteEffect,
        idempotency: Idempotency = Idempotency.None,
        deadline: Duration? = null,
        status: Int = 200,
        name: String? = null,
    ): Declares<Call<In, Out>> {
        val request = kSerializer<In>()
        val response = kSerializer<Out>()
        return Declares(name) { service, named -> Call(service, named, request, response, route, effect, idempotency, deadline, status, null) }
    }

    protected inline fun <reified In, reified Out> stream(
        route: Route? = null,
        effect: Effect = Effect.LiveRead,
        name: String? = null,
    ): Declares<Stream<In, Out>> {
        val request = kSerializer<In>()
        val element = kSerializer<Out>()
        return Declares(name) { service, named -> Stream(service, named, request, element, route, effect, null) }
    }

    protected inline fun <reified M> topic(
        name: String,
        delivery: Delivery = Delivery.AtLeastOnce,
        ordering: Ordering<M> = Ordering.None,
        capacity: Int = Channel.DefaultCapacity,
        overflow: Overflow = Overflow.Suspend,
        maxAttempts: Int = Channel.DefaultAttempts,
        deadLetter: Channel<M>? = null,
    ): Topic<M> = declare(Topic("$id/$name", kSerializer(), delivery, ordering, capacity, overflow, maxAttempts, deadLetter))

    protected inline fun <reified M> queue(
        name: String,
        delivery: Delivery = Delivery.AtLeastOnce,
        ordering: Ordering<M> = Ordering.None,
        capacity: Int = Channel.DefaultCapacity,
        overflow: Overflow = Overflow.Suspend,
        maxAttempts: Int = Channel.DefaultAttempts,
        deadLetter: Channel<M>? = null,
    ): Queue<M> = declare(Queue("$id/$name", kSerializer(), delivery, ordering, capacity, overflow, maxAttempts, deadLetter))

    protected fun <In, Out> Call<In, Out>.handle(block: suspend (In) -> Out): Call<In, Out> = answer(answering(block))

    protected fun <In, Out> Stream<In, Out>.handle(block: (In) -> Flow<Out>): Stream<In, Out> = answer(answering(block))

    protected inline fun <reified S : Service> inProcess(): S = link.served?.service as? S
        ?: throw UnsupportedOperationException("$id serves this member only in-process, and a ${link.transport} link cannot carry it")

    internal fun bind(link: Link) {
        check(this.link === Unlinked) { "$id is already linked over ${this.link.transport}" }
        this.link = link
    }

    @PublishedApi
    internal fun <O : Operation<*, *>> declare(operation: O): O {
        require(declared.put(operation.name, operation) == null) { "Service '$id' declares '${operation.name}' twice" }
        return operation
    }

    @PublishedApi
    internal fun <C : Channel<*>> declare(channel: C): C {
        require(declaredChannels.none { it.id == channel.id }) { "Service '$id' declares channel '${channel.id}' twice" }
        declaredChannels += channel
        return channel
    }

    private fun <O : Operation<*, *>> answer(operation: O): O {
        require(operation.service === this) { "${operation.id} belongs to another instance of '$id'" }
        answered[operation.name] = operation
        return operation
    }

    override fun toString(): String = "Service($id@$fingerprint)"
}

infix fun <S : Service> S.over(link: Link): S = apply { bind(link) }

class Declares<O : Operation<*, *>> @PublishedApi internal constructor(
    private val name: String?,
    private val create: (Service, String) -> O,
) : PropertyDelegateProvider<Service, ReadOnlyProperty<Service, O>> {
    override fun provideDelegate(thisRef: Service, property: KProperty<*>): ReadOnlyProperty<Service, O> {
        val operation = thisRef.declare(create(thisRef, name ?: property.name))
        return ReadOnlyProperty { _, _ -> operation }
    }
}

object Unlinked : Link() {
    override val transport: String get() = "none"

    override suspend fun <In, Out> call(operation: Call<In, Out>, request: In): Out = throw unanswered(operation)

    override fun <In, Out> stream(operation: Stream<In, Out>, request: In): Flow<Out> = throw unanswered(operation)

    override suspend fun <In, Out> transmit(operation: Call<In, Out>, exchange: Exchange<In, Out>): Out = throw unanswered(operation)

    private fun unanswered(operation: Operation<*, *>) = Unavailable("${operation.id} is not answered by ${operation.service::class.simpleName ?: operation.service.id}", 501)
}
