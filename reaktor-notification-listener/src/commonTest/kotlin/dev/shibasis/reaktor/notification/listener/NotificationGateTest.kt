package dev.shibasis.reaktor.notification.listener

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NotificationGateTest {
    private fun posted(app: String) = PostedNotification(app, "k", 0, "t", "x", null)

    @Test
    fun anAppOutsideTheFilterIsNeverRead() {
        val gate = NotificationGate()
        gate.set(setOf("com.bank"))
        var reads = 0
        assertNull(gate.admit("com.chat") { reads++; posted("com.chat") })
        assertEquals(0, reads, "a notification from an app not in the filter was read")
        assertEquals("com.bank", gate.admit("com.bank") { reads++; posted("com.bank") }?.app)
        assertEquals(1, reads)
    }

    @Test
    fun emptyUntilSetAndReplacedWhenSet() {
        val gate = NotificationGate()
        assertFalse(gate.allows("com.bank"))
        gate.set(setOf("com.bank"))
        gate.set(setOf("com.upi"))
        assertFalse(gate.allows("com.bank"))
        assertEquals(true, gate.allows("com.upi"))
    }
}
