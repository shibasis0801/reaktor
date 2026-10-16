package dev.shibasis.reaktor.media

import dev.shibasis.reaktor.media.gallery.MediaPick
import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.url.URL
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume

internal suspend fun pickFile(accept: String, fallbackMimeType: String): MediaPick? {
    val file = chooseFile(accept) ?: return null
    val bytes = read(file) ?: return null
    val mimeType = file.type.ifBlank { fallbackMimeType }
    val media = if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) withTimeoutOrNull(5_000) { measure(file, mimeType) } else null
    return MediaPick(
        bytes = bytes,
        mimeType = mimeType,
        suggestedName = file.name,
        durationMillis = media?.durationMillis,
        width = media?.width,
        height = media?.height,
    )
}

private suspend fun chooseFile(accept: String): File? = suspendCancellableCoroutine { cont ->
    val input = (document.createElement("input") as HTMLInputElement).apply {
        type = "file"
        if (accept.isNotEmpty()) this.accept = accept
        style.display = "none"
    }
    document.body?.appendChild(input)
    var resolved = false
    val resolve: (File?) -> Unit = { result ->
        if (!resolved) {
            resolved = true
            input.remove()
            if (cont.isActive) cont.resume(result)
        }
    }
    input.onchange = { resolve(input.files?.item(0)) }
    input.oncancel = { resolve(null) }
    cont.invokeOnCancellation { resolve(null) }
    input.click()
}

private suspend fun read(file: File): ByteArray? = suspendCancellableCoroutine { cont ->
    val reader = FileReader()
    reader.onload = { if (cont.isActive) cont.resume(Int8Array(reader.result as ArrayBuffer).unsafeCast<ByteArray>()) }
    reader.onerror = { if (cont.isActive) cont.resume(null) }
    cont.invokeOnCancellation { reader.abort() }
    reader.readAsArrayBuffer(file)
}

private class Measured(val durationMillis: Long?, val width: Int?, val height: Int?)

private suspend fun measure(file: File, mimeType: String): Measured? = suspendCancellableCoroutine { cont ->
    val element = document.createElement(if (mimeType.startsWith("video/")) "video" else "audio") as HTMLMediaElement
    val address = URL.createObjectURL(file)
    fun finish(result: Measured?) {
        element.onloadedmetadata = null
        element.onerror = null
        element.removeAttribute("src")
        URL.revokeObjectURL(address)
        if (cont.isActive) cont.resume(result)
    }
    element.preload = "metadata"
    element.onloadedmetadata = {
        val seconds = element.duration
        val video = element as? HTMLVideoElement
        finish(Measured(
            durationMillis = seconds.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() },
            width = video?.videoWidth?.takeIf { it > 0 },
            height = video?.videoHeight?.takeIf { it > 0 },
        ))
    }
    element.onerror = { _, _, _, _, _ -> finish(null); null }
    cont.invokeOnCancellation { finish(null) }
    element.src = address
}
