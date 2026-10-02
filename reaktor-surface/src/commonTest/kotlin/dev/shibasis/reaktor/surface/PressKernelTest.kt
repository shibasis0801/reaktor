package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PressKernelTest {
    private val idle = PressProperties()
    private val busy = PressProperties(busy = true)

    @Test
    fun aBusyControlAdmitsNoNewContact() {
        val pressed = PressKernel.reduce(busy, PressState(), PressInput.Press(1))
        assertTrue(pressed.state.contacts.isEmpty())
    }

    @Test
    fun becomingBusyDropsTheContactsButKeepsFocusAndHover() {
        val held = PressState(focused = true, focusVisible = true, hovered = true, contacts = setOf(1, 2))
        val reconciled = PressKernel.reconcile(busy, held).state
        assertTrue(reconciled.contacts.isEmpty())
        assertTrue(reconciled.focused && reconciled.focusVisible && reconciled.hovered)
    }

    @Test
    fun aBusyControlRefusesActivationAndHold() {
        val holdable = busy.copy(holdable = true)
        assertTrue(PressKernel.reduce(holdable, PressState(), PressInput.Activate(1)).events.isEmpty())
        assertTrue(PressKernel.reduce(holdable, PressState(), PressInput.Hold(1)).events.isEmpty())
    }

    @Test
    fun anIdleControlActivatesOncePerSequence() {
        val first = PressKernel.reduce(idle, PressState(), PressInput.Activate(1))
        assertEquals(listOf<Pressed>(Activated), first.events)
        assertTrue(PressKernel.reduce(idle, first.state, PressInput.Activate(1)).events.isEmpty())
    }

    @Test
    fun aDisabledControlForgetsHoverAndContacts() {
        val held = PressState(hovered = true, contacts = setOf(3))
        val reconciled = PressKernel.reconcile(PressProperties(enabled = false), held).state
        assertTrue(reconciled.contacts.isEmpty() && !reconciled.hovered)
    }
}
