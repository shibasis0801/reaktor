package dev.shibasis.reaktor.media.image

import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.shibasis.reaktor.surface.compose.LocalSurfaceStudio
import dev.shibasis.reaktor.surface.compose.StudioValues
import dev.shibasis.reaktor.surface.compose.SurfaceCall
import dev.shibasis.reaktor.surface.compose.SurfaceField
import dev.shibasis.reaktor.surface.compose.choice
import coil3.compose.AsyncImage as CoilAsyncImage

@Composable
fun AsyncImage(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "url",
    contentScale: ContentScale = ContentScale.FillBounds,
    onSize: ((Size) -> Unit)? = null,
    onError: (() -> Unit)? = null,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) UrlImage(url, modifier, contentDescription, contentScale, onSize, onError)
    else studio.Element(SurfaceCall("Image", modifier, listOf(
        SurfaceField.text("url", url),
        SurfaceField.text("description", contentDescription),
        SurfaceField.choice("contentScale", contentScale, StudioValues.Scales),
    ))) { edits ->
        UrlImage(edits.value("url") ?: url, edits.modifier, edits.value("description") ?: contentDescription,
            edits.choice("contentScale", contentScale, StudioValues.Scales), onSize, onError)
    }
}

@Composable
private fun UrlImage(url: String, modifier: Modifier, contentDescription: String, contentScale: ContentScale, onSize: ((Size) -> Unit)?, onError: (() -> Unit)?) {
    CoilAsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = onSize?.let { report -> { loaded -> report(loaded.painter.intrinsicSize) } },
        onError = onError?.let { report -> { report() } },
    )
}

/** For images that exist only in memory — a fresh camera shot has no URL to load from yet. */
@Composable
fun AsyncImage(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    contentDescription: String = "image",
    contentScale: ContentScale = ContentScale.Fit,
    onSize: ((Size) -> Unit)? = null,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) BytesImage(bytes, modifier, contentDescription, contentScale, onSize)
    else studio.Element(SurfaceCall("Image", modifier, listOf(
        SurfaceField.text("description", contentDescription),
        SurfaceField.choice("contentScale", contentScale, StudioValues.Scales),
    ))) { edits ->
        BytesImage(bytes, edits.modifier, edits.value("description") ?: contentDescription, edits.choice("contentScale", contentScale, StudioValues.Scales), onSize)
    }
}

@Composable
private fun BytesImage(bytes: ByteArray, modifier: Modifier, contentDescription: String, contentScale: ContentScale, onSize: ((Size) -> Unit)?) {
    CoilAsyncImage(
        model = bytes,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = onSize?.let { report -> { loaded -> report(loaded.painter.intrinsicSize) } },
    )
}
