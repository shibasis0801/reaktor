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
     * desktop, object-URL keys on the web. Opaque here; resolve them through [FileAdapter].
     */
    val fileUris: List<String> = emptyList(),
    /** Package or bundle id of the app the share came from, where the platform reveals it. */
    val sourceApp: String? = null,
    /** Subject or title the sender attached, where there is one. */
    val title: String? = null,
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
}

var Feature.ShareReceiver by CreateSlot<ShareReceiver<*>>()
