package dev.shibasis.reaktor.media.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.browser.document
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

class WebVideoPlayerAdapter : VideoPlayerAdapter<Unit>(Unit) {
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Render(source: VideoSource, modifier: Modifier) {
        val address = remember(source) {
            when (source) {
                is VideoSource.Remote -> source.url
                is VideoSource.Local -> URL.createObjectURL(Blob(arrayOf(source.bytes.unsafeCast<Int8Array>()), BlobPropertyBag(source.mimeType)))
            }
        }
        DisposableEffect(address) { onDispose { if (source is VideoSource.Local) URL.revokeObjectURL(address) } }
        HtmlElementView(
            factory = {
                (document.createElement("video") as HTMLVideoElement).apply {
                    controls = true
                    autoplay = true
                    setAttribute("playsinline", "")
                    style.width = "100%"
                    style.height = "100%"
                    style.backgroundColor = "black"
                    style.setProperty("object-fit", "contain")
                }
            },
            modifier = modifier,
            update = { video -> if (video.src != address) video.src = address },
            onRelease = { video ->
                video.pause()
                video.removeAttribute("src")
                video.load()
            },
        )
    }
}
