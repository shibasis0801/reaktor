package dev.shibasis.reaktor.devtools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.atomicfu.atomic
import kotlin.time.TimeSource

/**
 * A monotonic reading shared by every fact this process produces.
 *
 * Wall clocks on phones jump — timezone changes, NTP corrections, a user editing the date — and a
 * timeline built on them shows spans travelling backwards. The desktop learns the offset once at
 * handshake and works in this clock afterwards.
 */
object DevToolsClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun nanos(): Long = origin.elapsedNow().inWholeNanoseconds
}

/**
 * A bounded, replayable stream of facts for one capability.
 *
 * Emission is lock-free and never suspends: facts are handed to a channel that drops rather than
 * blocks, and a single owner coroutine drains it into the retained buffer. An app that stutters
 * because a developer tool is slow is a worse outcome than a tool that admits it lost data, so the
 * drop is counted and reported as [FactPage.droppedSinceCursor] rather than hidden.
 */
class FactStream(
    val capability: String,
    private val capacity: Int = 512,
    private val policy: BufferPolicy = BufferPolicy.DropOldest,
) {
    private val sequence = atomic(0L)
    private val dropped = atomic(0L)

    /** Owner-only. Every read of it goes through [lock]; the emit path never touches it. */
    private val buffer = ArrayDeque<AgentFact>()
    private val lock = Mutex()

    private val inbox = Channel<AgentFact>(capacity, BufferOverflow.DROP_OLDEST) {
        dropped.incrementAndGet()
    }

    private val shared = MutableSharedFlow<AgentFact>(
        replay = 0,
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Live facts, for a subscribed workbench. Late subscribers use [since] to catch up. */
    val facts: SharedFlow<AgentFact> = shared

    /**
     * A fixed ring of the most recent facts, readable without suspending or taking a lock.
     *
     * [since] does both, and neither is safe on a thread that is in the middle of crashing. This
     * is written in place with a plain array store under an atomic cursor: a reader racing a
     * writer can see a torn view of one slot, which is an acceptable flaw in a crash report and
     * an unacceptable one in a deadlock.
     */
    private val recent = arrayOfNulls<AgentFact>(RecentCapacity)
    private val recentCursor = atomic(0)

    /**
     * Whether anything is recorded at all.
     *
     * A disabled stream costs one volatile read on the emit path, which is the budget a tool gets
     * inside an app it is supposed to be measuring.
     */
    var enabled: Boolean = true

    fun start(scope: CoroutineScope): Job = scope.launch {
        for (fact in inbox) {
            lock.withLock { retain(fact) }
            shared.emit(fact)
        }
    }

    /**
     * Records a fact.
     *
     * The caller supplies a builder rather than a value so a disabled stream allocates nothing.
     */
    fun emit(build: (sequence: Long, monotonicNanos: Long) -> AgentFact) {
        if (!enabled) return
        inbox.trySend(build(sequence.incrementAndGet(), DevToolsClock.nanos()))
    }

    /** Facts newer than [sinceSequence], oldest first, with the count lost before them. */
    suspend fun since(sinceSequence: Long, limit: Int = capacity): FactPage = lock.withLock {
        FactPage(
            buffer.asSequence().filter { it.sequence > sinceSequence }.take(limit).toList(),
            dropped.value,
        )
    }

    suspend fun latest(limit: Int = capacity): FactPage = lock.withLock {
        FactPage(buffer.takeLast(limit), dropped.value)
    }

    suspend fun clear() = lock.withLock {
        buffer.clear()
        dropped.value = 0
    }

    /** Facts around a crash, newest last. Safe to call from a dying thread. */
    fun recentFacts(limit: Int = RecentCapacity): List<AgentFact> {
        val end = recentCursor.value
        val count = minOf(limit, RecentCapacity)
        return (0 until count)
            .map { offset -> recent[((end - count + offset) % RecentCapacity + RecentCapacity) % RecentCapacity] }
            .filterNotNull()
    }

    private fun retain(fact: AgentFact) {
        recent[recentCursor.value % RecentCapacity] = fact
        recentCursor.incrementAndGet()
        when (policy) {
            BufferPolicy.Conflate -> {
                buffer.clear()
                buffer.addLast(fact)
            }

            BufferPolicy.DropOldest -> {
                buffer.addLast(fact)
                while (buffer.size > capacity) {
                    buffer.removeFirst()
                    dropped.incrementAndGet()
                }
            }
        }
    }
}

data class FactPage(val facts: List<AgentFact>, val droppedSinceCursor: Long)

private const val RecentCapacity = 64
