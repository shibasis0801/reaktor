package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.serialization.KSerializer

enum class Distribution { FanOut, Competing }

enum class Delivery { AtMostOnce, AtLeastOnce }

enum class Overflow { Suspend, DropOldest, DropNewest, Reject }

sealed class Ordering<in M> {
    data object None : Ordering<Any?>()
    data object Total : Ordering<Any?>()
    class PerKey<M>(val key: (M) -> String) : Ordering<M>()
}

sealed class Channel<M>(
    val id: String,
    val message: KSerializer<M>,
    val delivery: Delivery,
    val ordering: Ordering<M>,
    val capacity: Int,
    val overflow: Overflow,
    val maxAttempts: Int,
    val deadLetter: Channel<M>?,
) {
    init {
        require(id.isNotBlank()) { "A channel needs an id" }
        require(capacity > 0) { "Channel '$id' needs a positive capacity" }
        require(maxAttempts > 0) { "Channel '$id' needs at least one attempt" }
    }

    abstract val distribution: Distribution

    val family: String get() = if (distribution == Distribution.FanOut) "topic" else "queue"

    fun keyOf(message: M): String? = (ordering as? Ordering.PerKey<M>)?.key?.invoke(message)

    override fun toString(): String = "$family($id)"

    companion object {
        const val DefaultCapacity = 256
        const val DefaultAttempts = 5
    }
}

class Topic<M>(
    id: String,
    message: KSerializer<M>,
    delivery: Delivery = Delivery.AtLeastOnce,
    ordering: Ordering<M> = Ordering.None,
    capacity: Int = DefaultCapacity,
    overflow: Overflow = Overflow.Suspend,
    maxAttempts: Int = DefaultAttempts,
    deadLetter: Channel<M>? = null,
) : Channel<M>(id, message, delivery, ordering, capacity, overflow, maxAttempts, deadLetter) {
    override val distribution: Distribution get() = Distribution.FanOut
}

class Queue<M>(
    id: String,
    message: KSerializer<M>,
    delivery: Delivery = Delivery.AtLeastOnce,
    ordering: Ordering<M> = Ordering.None,
    capacity: Int = DefaultCapacity,
    overflow: Overflow = Overflow.Suspend,
    maxAttempts: Int = DefaultAttempts,
    deadLetter: Channel<M>? = null,
) : Channel<M>(id, message, delivery, ordering, capacity, overflow, maxAttempts, deadLetter) {
    override val distribution: Distribution get() = Distribution.Competing
}

inline fun <reified M : Any> topic(
    id: String,
    delivery: Delivery = Delivery.AtLeastOnce,
    ordering: Ordering<M> = Ordering.None,
    capacity: Int = Channel.DefaultCapacity,
    overflow: Overflow = Overflow.Suspend,
    maxAttempts: Int = Channel.DefaultAttempts,
    deadLetter: Channel<M>? = null,
): Topic<M> = Topic(id, kSerializer(), delivery, ordering, capacity, overflow, maxAttempts, deadLetter)

inline fun <reified M : Any> queue(
    id: String,
    delivery: Delivery = Delivery.AtLeastOnce,
    ordering: Ordering<M> = Ordering.None,
    capacity: Int = Channel.DefaultCapacity,
    overflow: Overflow = Overflow.Suspend,
    maxAttempts: Int = Channel.DefaultAttempts,
    deadLetter: Channel<M>? = null,
): Queue<M> = Queue(id, kSerializer(), delivery, ordering, capacity, overflow, maxAttempts, deadLetter)
