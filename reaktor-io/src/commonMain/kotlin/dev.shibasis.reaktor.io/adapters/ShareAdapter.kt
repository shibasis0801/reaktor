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
class SharePayload(
    val fileName: String,
    val bytes: ByteArray,
    val mimeType: String = "application/json",
    /** Sheet heading; defaults to the file name. */
    val title: String? = null,
) {
    /**
     * Text convenience, for the documents most apps share — a JSON export, a log, a report.
     *
     * Bytes are the primary form because plenty of what an app wants to hand out is not text at
     * all: an image of a chart, a PDF, a recording. Encoding those through a String would corrupt
     * them, and every caller would have to know that.
     */
    constructor(
        fileName: String,
        contents: String,
        mimeType: String = "application/json",
        title: String? = null,
    ) : this(fileName, contents.encodeToByteArray(), mimeType, title)
}

abstract class ShareAdapter<Controller>(controller: Controller) : Adapter<Controller>(controller) {
    /**
     * Offers [payload] to the user through the platform's share UI.
     *
     * Returns false when the sheet could not be shown — no installed app accepts the type, or
     * there is no foreground context to show it from — so callers can say so rather than
     * reporting a success the user never saw.
     */
    abstract suspend fun shareFile(payload: SharePayload): Boolean

    /**
     * Offers [text] itself, rather than a document containing it.
     *
     * Separate from [shareFile] because the destination treats them as different things: a link
     * or a message shared as text lands in a chat as something the recipient can read and tap,
     * while the same string shared as a file arrives as an attachment nobody opens. Anything
     * meant to be pasted belongs here.
     *
     * [subject] fills in where a target asks for one — an email's subject line, mostly — and is
     * ignored everywhere else.
     */
    abstract suspend fun shareText(text: String, title: String? = null, subject: String? = null): Boolean
}

var Feature.Share by CreateSlot<ShareAdapter<*>>()
