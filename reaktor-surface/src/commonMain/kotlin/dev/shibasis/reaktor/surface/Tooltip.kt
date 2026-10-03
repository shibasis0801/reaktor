package dev.shibasis.reaktor.surface

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class TooltipProperties(val enabled: Boolean = true)

data class TooltipState(
    val shown: Boolean = false,
    val anchorHovered: Boolean = false,
    val tipHovered: Boolean = false,
    val focusVisible: Boolean = false,
    val suppressed: Boolean = false,
    val pending: Ticket? = null,
    val nextTicket: Long = 1,
)

sealed interface TooltipInput {
    data class AnchorHover(val inside: Boolean, val warm: Boolean) : TooltipInput
    data class TipHover(val inside: Boolean) : TooltipInput
    data class Focus(val focused: Boolean, val visible: Boolean) : TooltipInput
    data object Press : TooltipInput
    data object Escape : TooltipInput
    data class Elapsed(val ticket: Ticket) : TooltipInput
}

sealed interface TooltipEvent {
    data object Shown : TooltipEvent
    data object Hidden : TooltipEvent
}

data class TooltipKernel(
    val delay: Duration = 350.milliseconds,
    val linger: Duration = 120.milliseconds,
) : BehaviorKernel<TooltipProperties, TooltipState, TooltipInput, TooltipEvent> {
    override fun initial(properties: TooltipProperties) = TooltipState()

    override fun reduce(properties: TooltipProperties, state: TooltipState, input: TooltipInput): Reduction<TooltipState, TooltipEvent> =
        when (input) {
            is TooltipInput.AnchorHover ->
                if (input.inside) settle(properties, state.copy(anchorHovered = true), at = if (input.warm) Duration.ZERO else delay)
                else settle(properties, state.copy(anchorHovered = false, suppressed = false), at = linger)
            is TooltipInput.TipHover -> settle(properties, state.copy(tipHovered = input.inside), at = linger)
            is TooltipInput.Focus -> {
                val visible = input.focused && input.visible
                if (visible == state.focusVisible) Reduction(state)
                else settle(properties, state.copy(focusVisible = visible, suppressed = false), at = Duration.ZERO)
            }
            TooltipInput.Press, TooltipInput.Escape -> settle(properties, state.copy(suppressed = true), at = Duration.ZERO)
            is TooltipInput.Elapsed ->
                if (input.ticket != state.pending) Reduction(state)
                else turn(state.copy(pending = null), properties.enabled && state.wanted)
        }

    override fun reconcile(properties: TooltipProperties, state: TooltipState): Reduction<TooltipState, TooltipEvent> =
        if (!properties.enabled && state.shown) turn(state.copy(pending = null), false, cancel(state.pending)) else Reduction(state)

    private fun settle(properties: TooltipProperties, state: TooltipState, at: Duration): Reduction<TooltipState, TooltipEvent> {
        val wanted = properties.enabled && state.wanted
        val cancel = cancel(state.pending)
        val idle = state.copy(pending = null)
        return when {
            wanted == state.shown -> Reduction(idle, commands = cancel)
            at == Duration.ZERO -> turn(idle, wanted, cancel)
            else -> {
                val ticket = Ticket(state.nextTicket)
                Reduction(
                    state.copy(pending = ticket, nextTicket = state.nextTicket + 1),
                    commands = cancel + LocalCommand.Schedule(ticket, at, TooltipInput.Elapsed(ticket)),
                )
            }
        }
    }

    private fun turn(state: TooltipState, shown: Boolean, commands: List<LocalCommand> = emptyList()): Reduction<TooltipState, TooltipEvent> =
        if (state.shown == shown) Reduction(state, commands = commands)
        else Reduction(state.copy(shown = shown), listOf(if (shown) TooltipEvent.Shown else TooltipEvent.Hidden), commands = commands)

    private fun cancel(ticket: Ticket?): List<LocalCommand> = listOfNotNull(ticket?.let(LocalCommand::Cancel))
}

private val TooltipState.wanted: Boolean get() = !suppressed && (anchorHovered || tipHovered || focusVisible)
