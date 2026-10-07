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

/**
 * [name] as something that is safe to create a file called.
 *
 * A file name handed to a share adapter is very often somebody else's — the name a document
 * arrived under, from another device or another person — and every adapter turns it into a path.
 * `../../databases/app.db` is a name too, and written under a staging directory it is the app's
 * own database. So: the last path segment only, no separators of either kind, no control
 * characters, no leading dots (a file that is saved and cannot be seen was not saved, as far as
 * the person is concerned), and never empty.
 */
fun safeFileName(name: String): String {
    val leaf = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = buildString {
        for (char in leaf) {
            append(if (char < ' ' || char == ':' || char == '\u007f') '_' else char)
        }
    }.trim().trimStart('.').trim()
    return cleaned.ifEmpty { "file" }.take(MAX_FILE_NAME)
}

/** Under every filesystem's limit with room for " (12)" before the extension. */
private const val MAX_FILE_NAME = 200

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
     * Shows [payload] to the user, in whatever opens that kind of file.
     *
     * Not the same request as [shareFile]. Sharing asks "where should this go"; opening asks "let
     * me look at it", and on a phone those are different sheets — a photo somebody sent is to be
     * seen first, and only sometimes passed on. Where a platform has no such distinction, handing
     * the file over is the nearest thing, which is what this does unless an adapter knows better.
     */
    open suspend fun openFile(payload: SharePayload): Boolean = shareFile(payload)

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
