package dev.shibasis.reaktor.unlock

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/** What the device would ask for, so an app can name it ("Unlock with Face ID"). */
enum class UnlockKind {
    /** No screen lock is set: there is nothing to ask for. */
    None,

    /** The screen lock's PIN, pattern or password only. */
    Credential,
    Fingerprint,
    Face,

    /** Fingerprint or face, and the device doesn't say which (or has both). */
    Biometric,
}

/** How asking went. */
sealed interface Unlock {
    /** The owner confirmed: by fingerprint, by face, or with the screen lock. */
    data object Unlocked : Unlock

    /** They backed out, or the system took the prompt away (a call came in). Nothing is wrong; ask again when they do. */
    data object Cancelled : Unlock

    /** The device has no screen lock, so it can't confirm anyone. */
    data object Unavailable : Unlock

    /** The system refused for another reason. [message] is the system's own, for a log, not for the screen. */
    data class Failed(val message: String) : Unlock
}

/**
 * The device's own unlock, asked for by an app: the same fingerprint, face or screen lock that
 * opens the phone. The app never sees any of it, only how it went.
 *
 * Biometrics always fall back to the screen lock's PIN, pattern or password, so a phone without a
 * fingerprint reader, a wet finger or a face in the dark can still get in.
 */
interface DeviceUnlock {
    fun kind(): UnlockKind

    /**
     * Shows the system's prompt and waits for it. [title] heads the prompt where the system shows
     * one (Android); [reason] says why, in the app's words ("To see your payments").
     */
    suspend fun ask(title: String, reason: String): Unlock

    /**
     * Keeps the app's screen out of the app switcher while [hidden]: a locked app whose last
     * screen shows in the switcher isn't locked. On Android before 13 the only way to do that also
     * stops screenshots of the app.
     */
    fun hideContents(hidden: Boolean)
}

/** Empty where the device has no unlock to ask for (the desktop). */
var Feature.Unlock by CreateSlot<DeviceUnlock>()
