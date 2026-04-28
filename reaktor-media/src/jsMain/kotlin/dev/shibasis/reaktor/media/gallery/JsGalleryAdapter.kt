package dev.shibasis.reaktor.media.gallery

import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlinx.browser.document
import kotlin.coroutines.resume

class JsGalleryAdapter : GalleryAdapter<Unit>(Unit) {
    override suspend fun pickImage(): MediaPick? = suspendCancellableCoroutine { cont ->
        val input = (document.createElement("input") as HTMLInputElement).apply {
            type = "file"
            accept = "image/*"
            style.display = "none"
        }
        document.body?.appendChild(input)

        var resolved = false
        val resolve: (MediaPick?) -> Unit = { result ->
            if (!resolved) {
                resolved = true
                input.remove()
                if (cont.isActive) cont.resume(result)
            }
        }

        input.onchange = {
            val file: File? = input.files?.item(0)
            if (file == null) {
                resolve(null)
            } else {
                val reader = FileReader()
                reader.onload = {
                    val buffer = reader.result as ArrayBuffer
                    val view = Int8Array(buffer)
                    val bytes = ByteArray(view.length) { i -> view.asDynamic()[i].unsafeCast<Byte>() }
                    resolve(
                        MediaPick(
                            bytes = bytes,
                            mimeType = file.type.ifBlank { "image/*" },
                            suggestedName = file.name,
                        ),
                    )
                }
                reader.onerror = { resolve(null) }
                reader.readAsArrayBuffer(file)
            }
        }
        input.oncancel = { resolve(null) }

        cont.invokeOnCancellation { resolve(null) }
        input.click()
    }
}
