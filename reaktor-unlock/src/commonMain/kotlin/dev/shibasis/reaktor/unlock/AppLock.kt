package dev.shibasis.reaktor.unlock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When an app that locks is locked: the policy every such app would otherwise write again.
 *
 * - Locked when the process starts, if the lock is on.
 * - Locked again after the app has been off the screen for [graceMs]. Not at once: sharing a file
 *   or picking one leaves the app for a moment, and coming back from that shouldn't ask again.
 * - Not locked by turning the lock on: the person doing that is already in.
 *
 * It draws nothing and asks nothing by itself. The app shows its own lock screen while [locked]
 * is true, keeps its contents off the screen while it is null (the setting isn't read yet), tells
 * this when it leaves and returns to the screen, and calls [ask] from its Unlock button.
 *
 * One per process, held by whatever outlives the screens: an Activity comes and goes while the
 * process, and so the lock's state, carries on.
 */
class AppLock(
    private val now: () -> Long,
    /** How long the app can be off the screen before it locks again. */
    val graceMs: Long = 60_000,
) {
    private val state = MutableStateFlow<Boolean?>(null)
    private val asking = Mutex()
    private var enabled = false
    private var leftAt: Long? = null

    /** True: show the lock screen. False: show the app. Null: not known yet, show neither. */
    val locked: StateFlow<Boolean?> = state.asStateFlow()

    /** Once per process, when the app has read whether its lock is on. Later calls change nothing. */
    fun start(enabled: Boolean) {
        if (state.value != null) return
        this.enabled = enabled
        state.value = enabled
    }

    /** The person turned the lock on or off, from inside the app. */
    fun set(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled || state.value == null) state.value = false
    }

    /** The app left the screen. */
    fun left() {
        if (state.value == false && leftAt == null) leftAt = now()
    }

    /** The app is back on the screen. */
    fun returned() {
        val at = leftAt ?: return
        leftAt = null
        if (enabled && now() - at >= graceMs) state.value = true
    }

    /**
     * Asks the device and opens the lock if its owner confirms. One prompt at a time: a second
     * call while one is showing waits for it and returns its answer's effect, not a second prompt.
     *
     * A device whose screen lock has been taken off can confirm no one; the phone itself is open
     * to whoever holds it, so the app opens too, and [Unlock.Unavailable] tells the app to turn
     * its lock off and say why.
     */
    suspend fun ask(unlock: DeviceUnlock, title: String, reason: String): Unlock = asking.withLock {
        if (state.value == false) return@withLock Unlock.Unlocked
        val result = unlock.ask(title, reason)
        if (result == Unlock.Unlocked || result == Unlock.Unavailable) {
            leftAt = null
            state.value = false
        }
        result
    }
}
