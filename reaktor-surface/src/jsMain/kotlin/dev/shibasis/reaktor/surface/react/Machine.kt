package dev.shibasis.reaktor.surface.react

import dev.shibasis.reaktor.surface.*
import react.*

/** A React-owned interpreter for the same reductions used by the Compose adapter. */
class ReactMachine<P : Any, S : Any, I : Any, E : Any> internal constructor(
    private val kernel: BehaviorKernel<P, S, I, E>,
    private var properties: P,
) {
    var state: S = kernel.initial(properties)
        private set
    internal var changed: () -> Unit = {}
    internal var event: (E) -> Unit = {}
    internal var cue: (FeedbackCue) -> Unit = {}
    internal var reveal: (PartKey) -> Unit = {}
    private var pendingFocus: PartKey? = null
    private var live = true
    private var sequence = 0L
    private val timers = mutableMapOf<Ticket, Int>()
    private val parts = mutableMapOf<PartKey, dynamic>()
    private val queue = ArrayDeque<() -> Reduction<S, E>>()
    private var draining = false

    fun nextSequence(): Long = ++sequence
    fun send(input: I) = enqueue { kernel.reduce(properties, state, input) }
    fun part(key: PartKey): (dynamic) -> Unit = { node ->
        if (node == null) parts.remove(key) else {
            parts[key] = node
            if (pendingFocus == key) { pendingFocus = null; node.focus() }
        }
    }
    internal fun activate() { live = true }
    internal fun reconcile(next: P) {
        if (properties == next) return
        properties = next
        enqueue { kernel.reconcile(next, state) }
    }
    internal fun retire() {
        live = false
        queue.clear()
        timers.values.forEach { js("globalThis").clearTimeout(it) }
        timers.clear()
        pendingFocus = null
    }
    private fun enqueue(step: () -> Reduction<S, E>) {
        if (!live) return
        queue.addLast(step)
        if (draining) return
        draining = true
        try {
            while (live) {
                val reduced = queue.removeFirstOrNull()?.invoke() ?: break
                if (state != reduced.state) { state = reduced.state; changed() }
                reduced.events.forEach(event)
                reduced.cues.forEach(cue)
                reduced.commands.forEach(::execute)
            }
        } finally { draining = false }
    }
    @Suppress("UNCHECKED_CAST")
    private fun execute(command: LocalCommand) {
        val globals = js("globalThis")
        when (command) {
            is LocalCommand.Focus -> if (parts[command.part] != null) parts[command.part].focus() else { pendingFocus = command.part; reveal(command.part) }
            is LocalCommand.Reveal -> if (parts[command.part] != null) parts[command.part].scrollIntoView(attrs("block" to "nearest", "inline" to "nearest")) else reveal(command.part)
            is LocalCommand.Cancel -> timers.remove(command.ticket)?.let { globals.clearTimeout(it) }
            is LocalCommand.Schedule<*> -> {
                timers.remove(command.ticket)?.let { globals.clearTimeout(it) }
                timers[command.ticket] = globals.setTimeout({
                    timers.remove(command.ticket)
                    send(command.input as I)
                }, command.after.inWholeMilliseconds.toDouble()) as Int
            }
        }
    }
}

fun <P : Any, S : Any, I : Any, E : Any> useSurfaceMachine(
    kernel: BehaviorKernel<P, S, I, E>, properties: P,
    onCue: (FeedbackCue) -> Unit = {}, onEvent: (E) -> Unit,
): ReactMachine<P, S, I, E> {
    val environment = useSurfaceEnvironment()
    val machine = useMemo(kernel) { ReactMachine(kernel, properties) }
    val (_, refresh) = useState(0)
    // Event closures follow the committed render, including controlled properties.
    useLayoutEffectWithCleanup {
        machine.event = onEvent
        machine.cue = { environment.onCue(it); onCue(it) }
        machine.changed = { refresh { it + 1 } }
        machine.reconcile(properties)
    }
    useLayoutEffectWithCleanup(machine) {
        machine.activate()
        onCleanup { machine.retire() }
    }
    return machine
}

internal fun keyStroke(event: dynamic): KeyStroke? {
    val key = event.key as String
    val name = when (key) {
        "ArrowUp" -> KeyName.Up; "ArrowDown" -> KeyName.Down
        "ArrowLeft" -> KeyName.Left; "ArrowRight" -> KeyName.Right
        " " -> KeyName.Space; "[" -> KeyName.LeftBracket; "]" -> KeyName.RightBracket
        "=" -> KeyName.Equals; "-" -> KeyName.Minus; "," -> KeyName.Comma
        "." -> KeyName.Period; "/" -> KeyName.Slash
        else -> KeyName.entries.firstOrNull { it.name.equals(key, true) || it.name == "Digit$key" }
    } ?: return null
    return KeyStroke(name, meta = event.metaKey == true, control = event.ctrlKey == true,
        alt = event.altKey == true, shift = event.shiftKey == true, character = key.singleOrNull())
}

internal fun browserKeys(): KeyConvention =
    if ((js("(globalThis.navigator && globalThis.navigator.platform) || ''") as String).contains("Mac", true)) KeyConvention.Mac else KeyConvention.Pc
