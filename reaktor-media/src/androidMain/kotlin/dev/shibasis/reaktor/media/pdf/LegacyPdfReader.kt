package dev.shibasis.reaktor.media.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

@Composable
internal fun LegacyPdfReader(path: String, state: PdfReaderState, modifier: Modifier) {
    var renderer by remember(path) { mutableStateOf<PdfRenderer?>(null) }
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var page by remember(path) { mutableStateOf(state.state.value.page) }
    var zoom by remember(path) { mutableStateOf(1f) }
    var pan by remember(path) { mutableStateOf(Offset.Zero) }
    val rendering = remember(path) { Mutex() }
    LaunchedEffect(path) {
        var opened: PdfRenderer? = null
        try {
            opened = withContext(Dispatchers.IO) { PdfRenderer(ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)) }
            state.loaded(opened.pageCount, false)
            page = state.state.value.page
            renderer = opened
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { state.failed(error.message ?: "This PDF could not be opened.") }
        finally { renderer = null; withContext(NonCancellable + Dispatchers.IO) { rendering.withLock { opened?.close() } } }
    }
    LaunchedEffect(renderer, page) {
        val pdf = renderer ?: return@LaunchedEffect
        try { bitmap = withContext(Dispatchers.IO) { rendering.withLock {
            pdf.openPage(page).use { opened ->
                val width = 1200
                Bitmap.createBitmap(width, (width.toFloat() * opened.height / opened.width).toInt().coerceIn(1, 2400), Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(android.graphics.Color.WHITE)
                    opened.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        } }
        pan = Offset.Zero
        state.viewport(page, zoom)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { state.failed(error.message ?: "This PDF page could not be rendered.") }
    }
    LaunchedEffect(state) {
        state.commands.collectLatest { request -> when (val command = request?.second) {
            is PdfCommand.Page -> page = command.index
            is PdfCommand.Zoom -> { zoom = (zoom * command.factor).coerceIn(1f, 5f); state.viewport(page, zoom) }
            is PdfCommand.Search -> state.found(emptyList())
            null -> Unit
        } }
    }
    bitmap?.let { image -> Image(image.asImageBitmap(), "PDF page ${page + 1}", modifier.graphicsLayer {
        scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
    }.pointerInput(path) { detectTransformGestures { _, delta, scale, _ -> zoom = (zoom * scale).coerceIn(1f, 5f); pan += delta; state.viewport(page, zoom) } }) }
}
