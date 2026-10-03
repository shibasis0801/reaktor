package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class TooltipKernelTest {
    private val kernel = TooltipKernel()
    private val on = TooltipProperties()

    private fun TooltipState.after(vararg inputs: TooltipInput, properties: TooltipProperties = on): Reduction<TooltipState, TooltipEvent> =
        inputs.fold(Reduction<TooltipState, TooltipEvent>(this)) { last, input -> kernel.reduce(properties, last.state, input) }

    private fun Reduction<TooltipState, TooltipEvent>.timer(): LocalCommand.Schedule<*> =
        commands.filterIsInstance<LocalCommand.Schedule<*>>().single()

    private fun Reduction<TooltipState, TooltipEvent>.elapse(): Reduction<TooltipState, TooltipEvent> =
        kernel.reduce(on, state, timer().input as TooltipInput)

    private val hovered = TooltipInput.AnchorHover(inside = true, warm = false)
    private val left = TooltipInput.AnchorHover(inside = false, warm = false)

    @Test
    fun hoveringShowsTheTipAfterTheDelay() {
        val waiting = TooltipState().after(hovered)
        assertFalse(waiting.state.shown)
        assertEquals(350.milliseconds, waiting.timer().after)
        val shown = waiting.elapse()
        assertTrue(shown.state.shown)
        assertEquals(listOf<TooltipEvent>(TooltipEvent.Shown), shown.events)
    }

    @Test
    fun aWarmWindowShowsTheTipAtOnce() {
        val warm = TooltipState().after(TooltipInput.AnchorHover(inside = true, warm = true))
        assertTrue(warm.state.shown)
        assertEquals(listOf<TooltipEvent>(TooltipEvent.Shown), warm.events)
    }

    @Test
    fun leavingLingersAndTheTipItselfCanBeHovered() {
        val shown = TooltipState().after(hovered).elapse().state
        val leaving = shown.after(left)
        assertTrue(leaving.state.shown)
        assertEquals(120.milliseconds, leaving.timer().after)
        val onTip = leaving.state.after(TooltipInput.TipHover(true))
        assertTrue(LocalCommand.Cancel(leaving.timer().ticket) in onTip.commands)
        assertTrue(kernel.reduce(on, onTip.state, leaving.timer().input as TooltipInput).state.shown)
        val offTip = onTip.state.after(TooltipInput.TipHover(false))
        assertEquals(listOf<TooltipEvent>(TooltipEvent.Hidden), offTip.elapse().events)
    }

    @Test
    fun leavingBeforeTheDelayCancelsTheTip() {
        val waiting = TooltipState().after(hovered)
        val gone = waiting.state.after(left)
        assertTrue(LocalCommand.Cancel(waiting.timer().ticket) in gone.commands)
        assertNull(gone.state.pending)
        assertTrue(kernel.reduce(on, gone.state, waiting.timer().input as TooltipInput).events.isEmpty())
    }

    @Test
    fun aPressHidesTheTipUntilThePointerLeaves() {
        val shown = TooltipState().after(hovered).elapse().state
        val pressed = shown.after(TooltipInput.Press)
        assertEquals(listOf<TooltipEvent>(TooltipEvent.Hidden), pressed.events)
        assertTrue(pressed.state.after(TooltipInput.Focus(focused = true, visible = false)).commands.isEmpty())
        val back = pressed.state.after(left, hovered)
        assertFalse(back.state.shown)
        assertTrue(back.elapse().state.shown)
    }

    @Test
    fun escapeHidesTheTip() {
        val shown = TooltipState().after(hovered).elapse().state
        val escaped = shown.after(TooltipInput.Escape)
        assertEquals(listOf<TooltipEvent>(TooltipEvent.Hidden), escaped.events)
        assertFalse(escaped.state.shown)
    }

    @Test
    fun visibleKeyboardFocusShowsTheTipAtOnceAndBlurHidesIt() {
        val focused = TooltipState().after(TooltipInput.Focus(focused = true, visible = true))
        assertEquals(listOf<TooltipEvent>(TooltipEvent.Shown), focused.events)
        val blurred = focused.state.after(TooltipInput.Focus(focused = false, visible = false))
        assertEquals(listOf<TooltipEvent>(TooltipEvent.Hidden), blurred.events)
        val waiting = TooltipState().after(hovered)
        val pointerFocus = waiting.state.after(TooltipInput.Focus(focused = true, visible = false))
        assertEquals(waiting.state, pointerFocus.state)
    }

    @Test
    fun aStaleTicketIsIgnored() {
        val first = TooltipState().after(hovered)
        val second = first.state.after(left, hovered)
        assertTrue(kernel.reduce(on, second.state, first.timer().input as TooltipInput).events.isEmpty())
        assertTrue(second.elapse().state.shown)
    }

    @Test
    fun aDisabledTooltipNeverShowsAndDisablingHidesIt() {
        val off = TooltipProperties(enabled = false)
        val hoveredOff = TooltipState().after(TooltipInput.AnchorHover(inside = true, warm = true), properties = off)
        assertFalse(hoveredOff.state.shown)
        val shown = TooltipState().after(hovered).elapse().state
        assertEquals(listOf<TooltipEvent>(TooltipEvent.Hidden), kernel.reconcile(off, shown).events)
    }
}
