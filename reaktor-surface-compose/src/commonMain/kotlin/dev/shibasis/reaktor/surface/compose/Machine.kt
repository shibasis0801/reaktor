package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import dev.shibasis.reaktor.surface.BehaviorKernel
import dev.shibasis.reaktor.surface.FeedbackCue
import dev.shibasis.reaktor.surface.LocalCommand
import dev.shibasis.reaktor.surface.PartKey
import dev.shibasis.reaktor.surface.Reduction
import dev.shibasis.reaktor.surface.Ticket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
class Machine<P : Any, S : Any, I : Any, E : Any> internal constructor(
    private val kernel: BehaviorKernel<P, S, I, E>,
    private var properties: P,
    private val scope: CoroutineScope,
    private val onEvent: (E) -> Unit,
    private val onCue: (FeedbackCue) -> Unit,
) {
    var state: S by mutableStateOf(kernel.initial(properties))
        private set

    private val queue = ArrayDeque<() -> Reduction<S, E>>()
    private val parts = mutableMapOf<PartKey, Part>()
    private val timers = mutableMapOf<Ticket, Job>()
    private var draining = false
    private var retired = false
    private var sequence = 0L

    fun send(input: I) = enqueue { kernel.reduce(properties, state, input) }

    fun nextSequence(): Long = ++sequence

    internal fun reconcile(next: P) {
        if (next == properties) return
        properties = next
        enqueue { kernel.reconcile(properties, state) }
    }

    internal fun register(key: PartKey, part: Part) {
        parts[key] = part
    }

    internal fun unregister(key: PartKey, part: Part) {
        if (parts[key] === part) parts.remove(key)
    }

    internal fun retire() {
        retired = true
        queue.clear()
        timers.values.forEach(Job::cancel)
        timers.clear()
        parts.clear()
    }

    private fun enqueue(step: () -> Reduction<S, E>) {
        if (retired) return
        queue.addLast(step)
        if (draining) return
        draining = true
        try {
            while (!retired) {
                val reduction = queue.removeFirstOrNull()?.invoke() ?: break
                state = reduction.state
                reduction.events.forEach(onEvent)
                reduction.cues.forEach(onCue)
                reduction.commands.forEach(::execute)
            }
        } finally {
            draining = false
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun execute(command: LocalCommand) {
        when (command) {
            is LocalCommand.Focus -> parts[command.part]?.focus?.requestFocus()
            is LocalCommand.Reveal -> parts[command.part]?.let { part -> scope.launch { part.reveal.bringIntoView() } }
            is LocalCommand.Schedule<*> -> {
                timers.remove(command.ticket)?.cancel()
                timers[command.ticket] = scope.launch {
                    delay(command.after)
                    timers.remove(command.ticket)
                    send(command.input as I)
                }
            }
            is LocalCommand.Cancel -> timers.remove(command.ticket)?.cancel()
        }
    }
}

internal class Part @OptIn(ExperimentalFoundationApi::class) constructor(
    val focus: FocusRequester,
    val reveal: BringIntoViewRequester,
)

@Composable
fun <P : Any, S : Any, I : Any, E : Any> rememberMachine(
    kernel: BehaviorKernel<P, S, I, E>,
    properties: P,
    onEvent: (E) -> Unit,
): Machine<P, S, I, E> {
    val scope = rememberCoroutineScope()
    val event by rememberUpdatedState(onEvent)
    val cue by rememberUpdatedState(LocalCuePlayer.current)
    val machine = remember(kernel) { Machine(kernel, properties, scope, { event(it) }, { cue(it) }) }
    SideEffect { machine.reconcile(properties) }
    DisposableEffect(machine) { onDispose(machine::retire) }
    return machine
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.part(machine: Machine<*, *, *, *>, key: PartKey): Modifier {
    val focus = remember { FocusRequester() }
    val reveal = remember { BringIntoViewRequester() }
    DisposableEffect(machine, key) {
        val part = Part(focus, reveal)
        machine.register(key, part)
        onDispose { machine.unregister(key, part) }
    }
    return testId(key.value).focusRequester(focus).bringIntoViewRequester(reveal)
}

fun Modifier.testId(id: String): Modifier = tagsAsIds().testTag(id)
