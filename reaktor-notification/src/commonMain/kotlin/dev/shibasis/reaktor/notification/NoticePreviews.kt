package dev.shibasis.reaktor.notification

/**
 * Previews of sealed notices, as a notification client sees them: something that can open one as
 * it arrives, and the person's choice to keep previews hidden.
 *
 * A client holds one or none ([NotificationsClient.sealedPreviews]). The one implementation,
 * `SealedNoticePreviews`, lives in `:reaktor-notification-sealed` with the cryptography it needs;
 * this module stays free of it, so an app that only posts reminders links none.
 */
interface NoticePreviews {
    /**
     * The preview text for [notice], or null when it can't or shouldn't be shown: previews are
     * hidden, the keys aren't on this device, or the notice doesn't check out. [body] turns the
     * opened bytes into the words to show.
     */
    suspend fun open(notice: SealedNotice, body: (SealedNotice, ByteArray) -> String?): String?

    /** Whether the person keeps previews hidden. True until they say otherwise. */
    suspend fun hidden(): Boolean

    suspend fun setHidden(hidden: Boolean)

    /** Forgets the keys (signing out), keeping the choice to hide. */
    suspend fun clear()
}
