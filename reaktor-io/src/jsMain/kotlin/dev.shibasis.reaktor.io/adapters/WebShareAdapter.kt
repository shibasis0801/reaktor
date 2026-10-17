package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.web.TapSheet
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.coroutines.resume
import kotlin.js.Promise

class WebShareAdapter : ShareAdapter<Unit>(Unit) {
    override suspend fun shareText(text: String, title: String?, subject: String?): Boolean {
        val data = js("({})")
        data.text = text
        if (title != null) data.title = title
        return share(data, heading = title ?: "Share", copyable = text)
    }

    override suspend fun shareFile(payload: SharePayload): Boolean {
        val data = js("({})")
        data.files = arrayOf(payload.toFile())
        data.title = payload.title ?: payload.fileName
        if (canShare(data)) return share(data, heading = payload.title ?: payload.fileName, copyable = null)
        return offer(payload, action = "Download ${payload.fileName}", download = true)
    }

    override suspend fun openFile(payload: SharePayload): Boolean =
        offer(payload, action = "Open ${payload.fileName}", download = false)

    private suspend fun share(data: dynamic, heading: String, copyable: String?): Boolean {
        if (navigator().share == null) return copyable?.let { copyWithTap(heading, it) } ?: false
        return when (attempt(data)) {
            Outcome.Shared -> true
            Outcome.Cancelled -> false
            Outcome.NeedsTap -> shareWithTap(data, heading, copyable)
            Outcome.Failed -> copyable?.let { copyWithTap(heading, it) } ?: false
        }
    }

    private suspend fun attempt(data: dynamic): Outcome = try {
        navigator().share(data).unsafeCast<Promise<Any?>>().await()
        Outcome.Shared
    } catch (error: Throwable) {
        outcomeOf(error)
    }

    private suspend fun shareWithTap(data: dynamic, heading: String, copyable: String?): Boolean =
        suspendCancellableCoroutine { continuation ->
            val sheet = TapSheet.open(heading, message = copyable) {
                if (continuation.isActive) continuation.resume(false)
            }
            sheet.button("Share") {
                sheet.close()
                navigator().share(data).unsafeCast<Promise<Any?>>().then(
                    { if (continuation.isActive) continuation.resume(true) },
                    { error -> if (continuation.isActive) continuation.resume(outcomeOf(error) == Outcome.Shared) },
                )
            }
            if (copyable != null) sheet.button("Copy", primary = false) {
                sheet.close()
                if (continuation.isActive) continuation.resume(copy(copyable))
            }
            continuation.invokeOnCancellation { sheet.close() }
        }

    private suspend fun copyWithTap(heading: String, text: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val sheet = TapSheet.open(heading, message = text) {
                if (continuation.isActive) continuation.resume(false)
            }
            sheet.button("Copy") {
                sheet.close()
                if (continuation.isActive) continuation.resume(copy(text))
            }
            continuation.invokeOnCancellation { sheet.close() }
        }

    private suspend fun offer(payload: SharePayload, action: String, download: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            val address = objectUrl(payload)
            val sheet = TapSheet.open(payload.title ?: payload.fileName) {
                window.setTimeout({ revokeObjectUrl(address) }, 60_000)
                if (continuation.isActive) continuation.resume(false)
            }
            sheet.button(action) {
                sheet.close()
                val anchor = document.createElement("a") as HTMLAnchorElement
                anchor.href = address
                anchor.rel = "noopener"
                if (download) anchor.download = payload.fileName else anchor.target = "_blank"
                (document.body ?: document.documentElement)?.appendChild(anchor)
                anchor.click()
                anchor.remove()
                window.setTimeout({ revokeObjectUrl(address) }, 60_000)
                if (continuation.isActive) continuation.resume(true)
            }
            continuation.invokeOnCancellation { sheet.close() }
        }

    private fun copy(text: String): Boolean {
        val area = document.createElement("textarea") as HTMLTextAreaElement
        area.value = text
        area.setAttribute("readonly", "")
        area.style.cssText = "position:fixed;top:0;left:0;opacity:0;pointer-events:none;"
        (document.body ?: document.documentElement)?.appendChild(area)
        area.select()
        val copied = runCatching { document.asDynamic().execCommand("copy") == true }.getOrDefault(false)
        area.remove()
        if (copied) return true
        val clipboard = navigator().clipboard
        if (clipboard == null) return false
        clipboard.writeText(text)
        return true
    }

    private fun canShare(data: dynamic): Boolean {
        val navigator = navigator()
        if (navigator.share == null || navigator.canShare == null) return false
        return runCatching { navigator.canShare(data) == true }.getOrDefault(false)
    }

    private enum class Outcome { Shared, Cancelled, NeedsTap, Failed }

    private fun outcomeOf(error: dynamic): Outcome = when (error?.name as? String) {
        "AbortError" -> Outcome.Cancelled
        "NotAllowedError" -> Outcome.NeedsTap
        else -> Outcome.Failed
    }
}

private fun navigator(): dynamic = js("globalThis.navigator")

private fun SharePayload.toFile(): dynamic {
    val bytes = this.bytes.toUint8Array()
    val name = fileName
    val options = js("({})")
    options.type = mimeType
    return js("new File([bytes], name, options)")
}

private fun objectUrl(payload: SharePayload): String {
    val bytes = payload.bytes.toUint8Array()
    val options = js("({})")
    options.type = payload.mimeType
    val blob = js("new Blob([bytes], options)")
    return js("URL.createObjectURL(blob)") as String
}

private fun revokeObjectUrl(address: String) {
    js("URL.revokeObjectURL(address)")
}
