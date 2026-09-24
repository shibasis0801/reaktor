package dev.shibasis.reaktor.surface

import kotlin.jvm.JvmInline
import kotlin.time.Duration

interface BehaviorKernel<P : Any, S : Any, I : Any, E : Any> {
    fun initial(properties: P): S
    fun reduce(properties: P, state: S, input: I): Reduction<S, E>
    fun reconcile(properties: P, state: S): Reduction<S, E> = Reduction(state)
}

data class Reduction<S, E>(
    val state: S,
    val events: List<E> = emptyList(),
    val cues: List<FeedbackCue> = emptyList(),
    val commands: List<LocalCommand> = emptyList(),
)

enum class FeedbackCue { Selection, Impact, Success, Threshold }

@JvmInline
value class PartKey(val value: String)

@JvmInline
value class Ticket(val value: Long)

sealed interface LocalCommand {
    data class Focus(val part: PartKey) : LocalCommand
    data class Reveal(val part: PartKey) : LocalCommand
    data class Schedule<out I : Any>(val ticket: Ticket, val after: Duration, val input: I) : LocalCommand
    data class Cancel(val ticket: Ticket) : LocalCommand
}
