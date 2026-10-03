package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ToastKernelTest {
    private val saved = ToastEntry(1, "Saved", 4.seconds)
    private val copied = ToastEntry(2, "Copied", 2.seconds)

    private fun run(vararg inputs: ToastInput, from: ToastState = ToastState()): List<Reduction<ToastState, ToastEvent>> {
        var state = from
        return inputs.map { input -> ToastKernel.reduce(Unit, state, input).also { state = it.state } }
    }

    @Test
    fun holdingCancelsTheTimerAndReleasingRestartsTheFullDuration() {
        val (shown, held, stale, released, elapsed) = run(
            ToastInput.Show(saved),
            ToastInput.Hold(true),
            ToastInput.Elapsed(Ticket(1)),
            ToastInput.Hold(false),
            ToastInput.Elapsed(Ticket(2)),
        )
        assertEquals(listOf<LocalCommand>(LocalCommand.Schedule(Ticket(1), 4.seconds, ToastInput.Elapsed(Ticket(1)))), shown.commands)
        assertEquals(listOf<LocalCommand>(LocalCommand.Cancel(Ticket(1))), held.commands)
        assertEquals(saved, stale.state.shown)
        assertEquals(emptyList(), stale.events)
        assertEquals(listOf<LocalCommand>(LocalCommand.Schedule(Ticket(2), 4.seconds, ToastInput.Elapsed(Ticket(2)))), released.commands)
        assertEquals(listOf<ToastEvent>(ToastEvent.Hidden(saved)), elapsed.events)
        assertNull(elapsed.state.shown)
    }

    @Test
    fun theNextToastStaysHeldWhileThePointerIsStillOnIt() {
        val reductions = run(
            ToastInput.Show(saved),
            ToastInput.Show(copied),
            ToastInput.Hold(true),
            ToastInput.Dismiss(saved.id),
            ToastInput.Hold(false),
        )
        val dismissed = reductions[3]
        assertEquals(listOf(ToastEvent.Hidden(saved), ToastEvent.Shown(copied)), dismissed.events)
        assertEquals(emptyList(), dismissed.commands)
        assertEquals(listOf<LocalCommand>(LocalCommand.Schedule(Ticket(2), 2.seconds, ToastInput.Elapsed(Ticket(2)))), reductions[4].commands)
    }

    @Test
    fun holdingNothingOrHoldingTwiceChangesNothing() {
        val empty = ToastKernel.reduce(Unit, ToastState(), ToastInput.Hold(true))
        assertEquals(ToastState(), empty.state)
        val (_, held, again, released, releasedAgain) = run(
            ToastInput.Show(saved),
            ToastInput.Hold(true),
            ToastInput.Hold(true),
            ToastInput.Hold(false),
            ToastInput.Hold(false),
        )
        assertEquals(held.state, again.state)
        assertEquals(emptyList(), again.commands)
        assertEquals(released.state, releasedAgain.state)
        assertEquals(emptyList(), releasedAgain.commands)
    }

    @Test
    fun aStaleTicketNeverHidesTheNextToast() {
        val (_, _, dismissed, stale) = run(
            ToastInput.Show(saved),
            ToastInput.Show(copied),
            ToastInput.Dismiss(saved.id),
            ToastInput.Elapsed(Ticket(1)),
        )
        assertEquals(copied, dismissed.state.shown)
        assertEquals(copied, stale.state.shown)
        assertEquals(emptyList(), stale.events)
    }
}
