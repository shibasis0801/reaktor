package dev.shibasis.reaktor.io.adapters

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Sharing a file out of a page, which is downloading it.
 *
 * A browser's answer to "give this file to the person" is the download: it goes where their
 * downloads go and the browser shows that it happened, in its own interface, which is more than a
 * page could credibly claim on its own behalf. The Web Share API can also carry files, but only on
 * some browsers, only over https, and only inside a user gesture that has not been spent — a
 * download works wherever a page does.
 *
 * Must be called from a user gesture or shortly after one, like anything else that causes a
 * download.
 */
class BrowserShareAdapter : ShareAdapter<Unit>(Unit) {

    override suspend fun shareFile(payload: SharePayload): Boolean = runCatching {
        val document: dynamic = js("globalThis.document")
        val urls: dynamic = js("globalThis.URL")

        val bytes: dynamic = payload.bytes
        val options: dynamic = js("({})")
        options.type = payload.mimeType
        val blob: dynamic = js("(function (b, o) { return new Blob([b], o) })")(bytes, options)
        val address: String = urls.createObjectURL(blob)

        val link = document.createElement("a")
        link.href = address
        link.download = safeFileName(payload.fileName)
        link.style.display = "none"
        document.body.appendChild(link)
        link.click()
        link.remove()

        // Released later rather than now: the download reads from this address after `click`
        // returns, and revoking it straight away cancels some browsers' downloads.
        js("globalThis.setTimeout")({ urls.revokeObjectURL(address) }, REVOKE_AFTER_MILLIS)
        true
    }.getOrDefault(false)

    /** The share sheet where the browser has one, and the clipboard where it does not. */
    override suspend fun shareText(text: String, title: String?, subject: String?): Boolean = runCatching {
        val navigator: dynamic = js("globalThis.navigator")
        if (navigator.share != undefined) {
            val data: dynamic = js("({})")
            data.text = text
            if (title != null) data.title = title
            (navigator.share(data) as Promise<Unit>).await()
        } else {
            (navigator.clipboard.writeText(text) as Promise<Unit>).await()
        }
        true
    }.getOrDefault(false)

    private companion object {
        const val REVOKE_AFTER_MILLIS = 30_000
    }
}
