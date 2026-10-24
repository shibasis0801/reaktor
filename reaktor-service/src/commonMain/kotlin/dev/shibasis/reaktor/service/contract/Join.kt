package dev.shibasis.reaktor.service.contract

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as Buffer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class Keyed<T, K>(val key: (T) -> K, val time: (T) -> Instant)

fun <T, K> keyed(time: (T) -> Instant = { Clock.System.now() }, key: (T) -> K): Keyed<T, K> = Keyed(key, time)

class TemporalJoin<K, L, R, O>(
    val within: Duration,
    val lateness: Duration = Duration.ZERO,
    private val combine: (L, R) -> O,
) {
    private val lefts = mutableMapOf<K, MutableList<Stamped<L>>>()
    private val rights = mutableMapOf<K, MutableList<Stamped<R>>>()
    private var latest: Instant? = null

    var late: Long = 0
        private set

    val watermark: Instant? get() = latest?.minus(lateness)

    val pending: Int get() = lefts.values.sumOf { it.size } + rights.values.sumOf { it.size }

    fun left(key: K, at: Instant, value: L): List<O> =
        arrive(key, at, value, lefts, rights) { mine, theirs -> combine(mine, theirs) }

    fun right(key: K, at: Instant, value: R): List<O> =
        arrive(key, at, value, rights, lefts) { mine, theirs -> combine(theirs, mine) }

    private fun <A, B> arrive(
        key: K,
        at: Instant,
        value: A,
        own: MutableMap<K, MutableList<Stamped<A>>>,
        other: MutableMap<K, MutableList<Stamped<B>>>,
        pair: (A, B) -> O,
    ): List<O> {
        if (watermark?.let { at < it } == true) {
            late += 1
            return emptyList()
        }
        val matches = other[key].orEmpty().filter { (it.at - at).absoluteValue <= within }.map { pair(value, it.value) }
        own.getOrPut(key) { mutableListOf() } += Stamped(at, value)
        latest = latest?.let { maxOf(it, at) } ?: at
        evict()
        return matches
    }

    private fun evict() {
        val horizon = watermark?.minus(within) ?: return
        prune(lefts, horizon)
        prune(rights, horizon)
    }

    private fun <T> prune(side: MutableMap<K, MutableList<Stamped<T>>>, horizon: Instant) {
        val stamps = side.values.iterator()
        while (stamps.hasNext()) {
            val kept = stamps.next()
            kept.removeAll { it.at < horizon }
            if (kept.isEmpty()) stamps.remove()
        }
    }

    private class Stamped<T>(val at: Instant, val value: T)
}

fun <K, L, R, O> Flow<L>.joinWithin(
    other: Flow<R>,
    within: Duration,
    left: Keyed<L, K>,
    right: Keyed<R, K>,
    lateness: Duration = Duration.ZERO,
    combine: (L, R) -> O,
): Flow<O> = channelFlow {
    val join = TemporalJoin<K, L, R, O>(within, lateness, combine)
    val lock = Mutex()
    launch { this@joinWithin.collect { value -> lock.withLock { join.left(left.key(value), left.time(value), value) }.forEach { send(it) } } }
    launch { other.collect { value -> lock.withLock { join.right(right.key(value), right.time(value), value) }.forEach { send(it) } } }
}

suspend fun <T> firstSuccess(vararg attempts: suspend () -> T): T = quorum(1, *attempts).single()

suspend fun <T> quorum(needed: Int, vararg attempts: suspend () -> T): List<T> {
    require(needed in 1..attempts.size) { "A quorum of $needed needs at least $needed attempts, not ${attempts.size}" }
    return coroutineScope {
        val answers = Buffer<Result<T>>(attempts.size)
        val running = attempts.map { attempt ->
            async {
                answers.send(
                    try {
                        Result.success(attempt())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Result.failure(failure)
                    },
                )
            }
        }
        val agreed = mutableListOf<T>()
        val failures = mutableListOf<Throwable>()
        while (agreed.size < needed) {
            answers.receive().fold({ agreed += it }, { failures += it })
            if (failures.size > attempts.size - needed) {
                running.forEach { it.cancel() }
                throw failures.last().also { last -> failures.dropLast(1).forEach { last.addSuppressed(it) } }
            }
        }
        running.forEach { it.cancel() }
        agreed
    }
}
