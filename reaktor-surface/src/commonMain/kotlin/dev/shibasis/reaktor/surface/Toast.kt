package dev.shibasis.reaktor.surface

import kotlin.time.Duration

data class ToastEntry(val id: Long, val message: String, val duration: Duration)

data class ToastState(
    val shown: ToastEntry? = null,
    val waiting: List<ToastEntry> = emptyList(),
    val ticket: Ticket? = null,
    val nextTicket: Long = 1,
)

sealed interface ToastInput {
    data class Show(val entry: ToastEntry) : ToastInput
    data class Elapsed(val ticket: Ticket) : ToastInput
    data class Dismiss(val id: Long) : ToastInput
}

sealed interface ToastEvent {
    data class Shown(val entry: ToastEntry) : ToastEvent
    data class Hidden(val entry: ToastEntry) : ToastEvent
}

object ToastKernel : BehaviorKernel<Unit, ToastState, ToastInput, ToastEvent> {
    override fun initial(properties: Unit) = ToastState()

    override fun reduce(properties: Unit, state: ToastState, input: ToastInput): Reduction<ToastState, ToastEvent> = when (input) {
        is ToastInput.Show ->
            if (state.shown == null) present(state, input.entry, emptyList(), emptyList())
            else Reduction(state.copy(waiting = state.waiting + input.entry))
        is ToastInput.Elapsed ->
            if (input.ticket != state.ticket) Reduction(state)
            else next(state, emptyList())
        is ToastInput.Dismiss ->
            if (state.shown?.id != input.id) Reduction(state)
            else next(state, listOfNotNull(state.ticket?.let { LocalCommand.Cancel(it) }))
    }

    private fun next(state: ToastState, commands: List<LocalCommand>): Reduction<ToastState, ToastEvent> {
        val hidden = listOfNotNull(state.shown?.let { ToastEvent.Hidden(it) })
        val following = state.waiting.firstOrNull()
            ?: return Reduction(state.copy(shown = null, ticket = null), hidden, commands = commands)
        return present(state.copy(waiting = state.waiting.drop(1)), following, hidden, commands)
    }

    private fun present(
        state: ToastState,
        entry: ToastEntry,
        before: List<ToastEvent>,
        commands: List<LocalCommand>,
    ): Reduction<ToastState, ToastEvent> {
        val ticket = Ticket(state.nextTicket)
        return Reduction(
            state.copy(shown = entry, ticket = ticket, nextTicket = state.nextTicket + 1),
            before + ToastEvent.Shown(entry),
            commands = commands + LocalCommand.Schedule(ticket, entry.duration, ToastInput.Elapsed(ticket)),
        )
    }
}
