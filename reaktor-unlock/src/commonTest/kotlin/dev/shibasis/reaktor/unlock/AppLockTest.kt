package dev.shibasis.reaktor.unlock

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppLockTest {
    private var clock = 0L
    private fun lock() = AppLock(now = { clock }, graceMs = 60_000)

    private class Device(var answer: Unlock) : DeviceUnlock {
        var asked = 0
        override fun kind() = UnlockKind.Fingerprint
        override suspend fun ask(title: String, reason: String): Unlock { asked++; return answer }
        override fun hideContents(hidden: Boolean) = Unit
    }

    @Test
    fun nothingIsShownUntilTheSettingIsRead() {
        val lock = lock()
        assertNull(lock.locked.value)
        // Leaving and returning before then decide nothing.
        lock.left(); clock += 120_000; lock.returned()
        assertNull(lock.locked.value)
        lock.start(enabled = true)
        assertEquals(true, lock.locked.value)
        // The setting is read once: a later start doesn't unlock, or lock, anything.
        lock.start(enabled = false)
        assertEquals(true, lock.locked.value)
    }

    @Test
    fun anAppWithoutALockIsNeverLocked() {
        val lock = lock()
        lock.start(enabled = false)
        assertEquals(false, lock.locked.value)
        lock.left(); clock += 3_600_000; lock.returned()
        assertEquals(false, lock.locked.value)
    }

    @Test
    fun aMomentAwayDoesNotLockAndAMinuteDoes() = runTest {
        val lock = lock()
        val device = Device(Unlock.Unlocked)
        lock.start(enabled = true)
        assertEquals(Unlock.Unlocked, lock.ask(device, "Unlock", "To see your payments"))
        assertEquals(false, lock.locked.value)

        // The share sheet, a file picker: back within the minute.
        lock.left(); clock += 59_000; lock.returned()
        assertEquals(false, lock.locked.value)

        lock.left(); clock += 60_000; lock.returned()
        assertEquals(true, lock.locked.value)

        // Stopped twice before starting again (a rotation): the first leaving is the one that counts.
        lock.ask(device, "", "")
        lock.left(); clock += 50_000; lock.left(); clock += 20_000; lock.returned()
        assertEquals(true, lock.locked.value)
    }

    @Test
    fun backingOutLeavesItLocked() = runTest {
        val lock = lock()
        val device = Device(Unlock.Cancelled)
        lock.start(enabled = true)
        assertEquals(Unlock.Cancelled, lock.ask(device, "", ""))
        assertEquals(true, lock.locked.value)
        device.answer = Unlock.Failed("Too many attempts")
        lock.ask(device, "", "")
        assertEquals(true, lock.locked.value)
        // Leaving while locked doesn't start the clock: it is still locked on return, whenever that is.
        lock.left(); clock += 5_000; lock.returned()
        assertEquals(true, lock.locked.value)
    }

    @Test
    fun turningItOnDoesNotLockThePersonDoingIt() = runTest {
        val lock = lock()
        val device = Device(Unlock.Unlocked)
        lock.start(enabled = false)
        lock.set(enabled = true)
        assertEquals(false, lock.locked.value)
        lock.left(); clock += 61_000; lock.returned()
        assertEquals(true, lock.locked.value)
        lock.ask(device, "", "")
        lock.set(enabled = false)
        lock.left(); clock += 61_000; lock.returned()
        assertEquals(false, lock.locked.value)
    }

    @Test
    fun aPhoneWithNoScreenLockOpensAndSaysSo() = runTest {
        val lock = lock()
        val device = Device(Unlock.Unavailable)
        lock.start(enabled = true)
        assertEquals(Unlock.Unavailable, lock.ask(device, "", ""))
        assertEquals(false, lock.locked.value)
        // Already open: nothing is asked.
        assertEquals(Unlock.Unlocked, lock.ask(device, "", ""))
        assertEquals(1, device.asked)
    }
}
