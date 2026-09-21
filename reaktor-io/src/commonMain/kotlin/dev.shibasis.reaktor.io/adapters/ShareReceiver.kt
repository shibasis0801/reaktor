package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Content the operating system routed *into* this app.
 *
 * [ShareAdapter] is the outbound half — it hands a file to the platform share sheet. This is the
 * other direction, and the two are not symmetrical: sharing out is a call, while sharing in is an
 * event that can arrive at any time, including as the thing that cold-started the process.
 *
 * A [Flow] rather than a callback for exactly that reason. An app that only reads its launch intent
 * misses every share that arrives while it is already running, and an app that only registers a
 * listener after its UI is up misses the one that started it. [incoming] covers both.
 */
@Serializable
data class ReceivedShare(
    val mime: String,
    /** Plain text or a URL, when the share carried one. */
    val text: String? = null,
    /**
     * Platform-specific handles for shared files — `content://` on Android, absolute paths on
     * desktop, object-URL keys on the web. Opaque: resolve them with [ShareReceiver.read].
     */
    val fileUris: List<String> = emptyList(),
    /** Package or bundle id of the app the share came from, where the platform reveals it. */
    val sourceApp: String? = null,
    /** Subject or title the sender attached, where there is one. */
    val title: String? = null,
)

/**
 * One shared file, resolved.
 *
 * [name] is the file's own name where the platform knows it — a display name from a content
 * provider, a browser `File.name`, the last path segment — and never a handle. A handle is not a
 * name: `content://media/external/images/media/1042` is what an app that skips this step ends up
 * showing the user.
 */
class SharedFile(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
)

/**
 * Emits each share the OS routes to this app.
 *
 * Implementations must replay the share that cold-started the process to the first collector, and
 * must not replay it a second time — a share delivered twice becomes a duplicate the app has no way
 * to tell apart from the user deliberately sharing the same thing again.
 */
abstract class ShareReceiver<Controller>(controller: Controller) : Adapter<Controller>(controller) {
    abstract val incoming: Flow<ReceivedShare>

    /**
     * Resolves one of [ReceivedShare.fileUris] to its name, type and bytes.
     *
     * On the receiver rather than on [FileAdapter], because a share handle is not a path and only
     * the thing that produced it can say what it means. Android's `content://` needs the
     * `ContentResolver` of the activity the share arrived at; a browser's key names a `File` object
     * held in this receiver and reachable nowhere else. Neither is something a file adapter rooted
     * in the app sandbox could open.
     *
     * Null when the handle no longer resolves — a permission grant that ended with the activity, a
     * temporary file already swept — which is a normal outcome, not an error.
     */
    abstract suspend fun read(fileUri: String): SharedFile?
}

/** Every file a share carried, skipping the ones that no longer resolve. */
suspend fun ShareReceiver<*>.files(share: ReceivedShare): List<SharedFile> =
    share.fileUris.mapNotNull { read(it) }

var Feature.ShareReceiver by CreateSlot<ShareReceiver<*>>()
