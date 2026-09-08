package dev.shibasis.reaktor.io.adapters

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.js.Promise

/**
 * The four ways something gets into a web app: dropped on it, pasted into it, picked in a dialog,
 * or shared to it by the operating system.
 *
 * The last one is the only one that needs cooperation. A browser cannot hand a running page an OS
 * share; it POSTs the share to the URL named in the manifest's `share_target`, and the page that
 * eventually loads has to find the payload again. A service worker intercepts that POST, parks the
 * payload in the Cache API, and redirects to the app with an id — see [SHARE_TARGET_CONTRACT] for
 * the shape a worker must write, which is the whole of what this class requires of one.
 *
 * **Text pasted into the page is deliberately not captured.** Compose renders into a canvas in a
 * shadow root, so a document-level paste listener cannot tell whether the user was typing in a text
 * field: the event is retargeted to the container and the real target is invisible. Capturing text
 * there would double every paste into every field in the app. Files have no such ambiguity — no
 * text field accepts one — so a file paste is taken and text is left to the field it was aimed at.
 */
class BrowserShareReceiver(
    /** Where drops and pastes are listened for. The document, unless an app scopes it tighter. */
    private val target: dynamic = js("globalThis.document"),
) : ShareReceiver<Unit>(Unit) {

    private val shares = MutableSharedFlow<ReceivedShare>(
        // Matches the other receivers: a share can land before the UI is collecting — and on the
        // web it usually does, because a share target arrives during the page load.
        replay = 1,
        extraBufferCapacity = 8,
    )

    override val incoming: Flow<ReceivedShare> = shares.asSharedFlow()

    /**
     * Files this receiver has handed out handles for.
     *
     * A browser `File` is a live object, not a path, so the handle in [ReceivedShare.fileUris] is
     * an object URL and this map is what it means. Bounded: the oldest entries are revoked once
     * there are more than [HELD_LIMIT], because a page that runs for a week and takes a hundred
     * drops would otherwise pin every one of them in memory.
     */
    private val held = LinkedHashMap<String, dynamic>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        target.addEventListener("paste", { event: dynamic -> onPaste(event) })
        // Without a dragover handler that cancels, the browser navigates to the dropped file and
        // the app disappears — the drop never reaches the page at all.
        target.addEventListener("dragover", { event: dynamic -> event.preventDefault(); Unit })
        target.addEventListener("drop", { event: dynamic -> onDrop(event) })

        scope.launch { collectShareTarget() }
    }

    /**
     * Opens a file picker and emits what the user chooses.
     *
     * Must be called from a user gesture — a click handler — or the browser ignores it. There is no
     * way to detect that from here, which is why nothing is returned: a picker the user never sees
     * and a picker the user cancels look identical.
     */
    fun pick(accept: String = "*/*", multiple: Boolean = true) {
        val document: dynamic = js("globalThis.document")
        val input = document.createElement("input")
        input.type = "file"
        input.accept = accept
        input.multiple = multiple
        input.style.display = "none"

        input.addEventListener("change", { _: dynamic ->
            val files = handles(input.files)
            if (files.isNotEmpty()) {
                shares.tryEmit(
                    ReceivedShare(
                        mime = files.singleOrNull()?.mime ?: "*/*",
                        fileUris = files.map { it.handle },
                        title = files.singleOrNull()?.name,
                    ),
                )
            }
            input.remove()
            Unit
        })

        document.body.appendChild(input)
        input.click()
    }

    override suspend fun read(fileUri: String): SharedFile? {
        val file = held[fileUri] ?: return null

        return runCatching {
            val buffer = file.arrayBuffer().unsafeCast<Promise<dynamic>>().await()
            SharedFile(
                name = (file.name as? String)?.takeIf { it.isNotBlank() } ?: "shared",
                mime = (file.type as? String)?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream",
                bytes = bytesOf(buffer),
            )
        }.getOrNull()
    }

    /**
     * Drops a handle and the file behind it.
     *
     * Optional — [HELD_LIMIT] bounds this on its own — but an app that knows it is finished with a
     * large file can say so rather than waiting for eviction.
     */
    fun release(fileUri: String) {
        held.remove(fileUri) ?: return
        revoke(fileUri)
    }

    // ── Events ────────────────────────────────────────────────────────────────────────────────

    private fun onPaste(event: dynamic) {
        val clipboard = event.clipboardData ?: return
        val files = handles(clipboard.files)
        // Text is left alone on purpose; see the class comment.
        if (files.isEmpty()) return

        event.preventDefault()
        shares.tryEmit(
            ReceivedShare(
                mime = files.singleOrNull()?.mime ?: "*/*",
                fileUris = files.map { it.handle },
                title = files.singleOrNull()?.name,
            ),
        )
    }

    private fun onDrop(event: dynamic) {
        event.preventDefault()
        val transfer = event.dataTransfer ?: return

        val files = handles(transfer.files)
        if (files.isNotEmpty()) {
            shares.tryEmit(
                ReceivedShare(
                    mime = files.singleOrNull()?.mime ?: "*/*",
                    fileUris = files.map { it.handle },
                    title = files.singleOrNull()?.name,
                ),
            )
            return
        }

        // A dragged link or selection. Unambiguous in a way a paste is not: a drop lands where the
        // user aimed it, and a text field would have taken the event itself.
        val uri = (transfer.getData("text/uri-list") as? String)?.takeIf { it.isNotBlank() }
        val text = uri ?: (transfer.getData("text/plain") as? String)?.takeIf { it.isNotBlank() }
        if (text != null) {
            shares.tryEmit(
                ReceivedShare(
                    mime = if (uri != null) "text/uri-list" else "text/plain",
                    text = text,
                ),
            )
        }
    }

    // ── Share target ──────────────────────────────────────────────────────────────────────────

    /**
     * Picks up a share a service worker parked for us, and cleans up after it.
     *
     * The cache entries are deleted once read and the id is stripped from the address bar, because
     * both outlive the page: a reload of `?reaktorShare=…` would otherwise deliver the same share
     * again, and a bookmarked one would deliver it forever.
     */
    private suspend fun collectShareTarget() {
        val id = shareTargetId() ?: return

        runCatching {
            val caches: dynamic = js("globalThis.caches")
            val cache = caches.open(SHARE_CACHE).unsafeCast<Promise<dynamic>>().await()

            val manifestKey = "$SHARE_PATH$id"
            val response = cache.match(manifestKey).unsafeCast<Promise<dynamic>>().await()
                ?: return@runCatching

            val manifest = response.json().unsafeCast<Promise<dynamic>>().await()
            val handles = mutableListOf<String>()

            val keys = manifest.files.unsafeCast<Array<dynamic>?>().orEmpty()
            keys.forEach { entry ->
                val stored = cache.match(entry.key).unsafeCast<Promise<dynamic>>().await()
                    ?: return@forEach
                val blob = stored.blob().unsafeCast<Promise<dynamic>>().await()
                // The blob a cache hands back has no name and often no type: both were on the
                // uploaded File and only the manifest still carries them.
                handles += hold(named(blob, entry.name as? String, entry.mime as? String))
                cache.delete(entry.key)
            }
            cache.delete(manifestKey)

            val text = listOfNotNull(
                (manifest.text as? String)?.takeIf { it.isNotBlank() },
                (manifest.url as? String)?.takeIf { it.isNotBlank() },
            ).joinToString("\n").takeIf { it.isNotBlank() }

            if (text != null || handles.isNotEmpty()) {
                shares.tryEmit(
                    ReceivedShare(
                        mime = if (handles.isEmpty()) "text/plain" else "*/*",
                        text = text,
                        fileUris = handles,
                        title = (manifest.title as? String)?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }

        clearShareTargetId()
    }

    private fun shareTargetId(): String? {
        val search: String = js("globalThis.location ? globalThis.location.search : ''") as String
        if (search.isEmpty()) return null

        val params: dynamic = js("new URLSearchParams(search)")
        return (params.get(SHARE_QUERY) as? String)?.takeIf { it.isNotBlank() }
    }

    private fun clearShareTargetId() {
        js(
            """
            (function () {
                var url = new URL(globalThis.location.href);
                url.searchParams.delete('reaktorShare');
                globalThis.history.replaceState(null, '', url.toString());
            })()
            """,
        )
    }

    // ── Handles ───────────────────────────────────────────────────────────────────────────────

    private class Handle(val handle: String, val name: String?, val mime: String?)

    private fun handles(fileList: dynamic): List<Handle> {
        val count = (fileList?.length as? Int) ?: 0
        return (0 until count).mapNotNull { index ->
            val file = fileList[index] ?: return@mapNotNull null
            Handle(hold(file), file.name as? String, (file.type as? String)?.takeIf { it.isNotBlank() })
        }
    }

    private fun hold(file: dynamic): String {
        val handle = js("URL.createObjectURL(file)") as String
        held[handle] = file

        while (held.size > HELD_LIMIT) {
            val oldest = held.keys.first()
            held.remove(oldest)
            revoke(oldest)
        }

        return handle
    }

    private fun revoke(handle: String) {
        js("URL.revokeObjectURL(handle)")
    }

    /** Puts a name and a type back on a blob, so [read] reports the file rather than "shared". */
    private fun named(blob: dynamic, name: String?, mime: String?): dynamic =
        js("new File([blob], name || 'shared', { type: mime || blob.type || 'application/octet-stream' })")

    private fun bytesOf(buffer: dynamic): ByteArray {
        val view = js("new Uint8Array(buffer)")
        val bytes = ByteArray(view.length as Int)
        for (index in bytes.indices) {
            bytes[index] = (view[index] as Int).toByte()
        }
        return bytes
    }

    companion object {
        /**
         * What a service worker must write for a share to be picked up.
         *
         * ```js
         * // In the worker's fetch handler, for a POST to the manifest's share_target action:
         * const form = await event.request.formData();
         * const id = crypto.randomUUID();
         * const cache = await caches.open('reaktor-share-target');
         * const files = [];
         * let index = 0;
         * for (const file of form.getAll('files')) {
         *   const key = '/reaktor-share/' + id + '/' + index++;
         *   await cache.put(key, new Response(file));
         *   files.push({ key, name: file.name, mime: file.type });
         * }
         * await cache.put('/reaktor-share/' + id, new Response(JSON.stringify({
         *   title: form.get('title'), text: form.get('text'), url: form.get('url'), files
         * }), { headers: { 'Content-Type': 'application/json' } }));
         * return Response.redirect('/?reaktorShare=' + id, 303);
         * ```
         *
         * Cache rather than IndexedDB because the payload is `Response`-shaped already and the
         * worker holds it for milliseconds — long enough for one redirect.
         *
         * The name of the cache a worker parks a share in.
         */
        const val SHARE_CACHE = "reaktor-share-target"

        /** Key prefix inside that cache; the share's id and file index follow. */
        const val SHARE_PATH = "/reaktor-share/"

        /** Query parameter naming the parked share on the redirect that follows it. */
        const val SHARE_QUERY = "reaktorShare"

        /**
         * How many dropped files stay resolvable at once.
         *
         * Generous for the way this is used — an app resolves a handle within a moment of the drop
         * — and small enough that a long-lived tab cannot accumulate a session's worth of files it
         * never released.
         */
        const val HELD_LIMIT = 32
    }
}
