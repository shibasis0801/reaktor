package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/**
 * A document handed to the platform share sheet.
 *
 * [FileAdapter] writes inside the app sandbox, which the user cannot reach — exporting anything
 * (a backup, a report, a log bundle) means going through the OS sharing mechanism instead.
 */
data class SharePayload(
    val fileName: String,
    val contents: String,
    val mimeType: String = "application/json",
    /** Sheet heading; defaults to the file name. */
    val title: String? = null,
)

abstract class ShareAdapter<Controller>(controller: Controller) : Adapter<Controller>(controller) {
    /**
     * Offers [payload] to the user through the platform's share UI.
     *
     * Returns false when the sheet could not be shown — no installed app accepts the type, or
     * there is no foreground context to show it from — so callers can say so rather than
     * reporting a success the user never saw.
     */
    abstract suspend fun shareFile(payload: SharePayload): Boolean
}

var Feature.Share by CreateSlot<ShareAdapter<*>>()
