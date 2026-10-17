@file:Suppress("RestrictedApi")

package dev.shibasis.reaktor.media.pdf

import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.SparseArray
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.selection.Selection
import androidx.pdf.selection.model.TextSelection
import androidx.pdf.view.PdfView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun PdfReader(path: String, state: PdfReaderState, modifier: Modifier) {
    if (Build.VERSION.SDK_INT >= 28) AndroidXReader(path, state, modifier)
    else LegacyPdfReader(path, state, modifier)
}

@RequiresApi(28)
@Composable
private fun AndroidXReader(path: String, state: PdfReaderState, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember(path, state) { PdfView(context) }
    var ready by remember(path, state) { mutableStateOf(false) }
    LaunchedEffect(path, state) {
        var opened: PdfDocument? = null
        try {
            opened = SandboxedPdfLoader(context).openDocument(Uri.fromFile(File(path)))
            state.loaded(opened.pageCount, opened.isFeatureSupported(PdfFeature.SEARCH))
            val initialPage = state.state.value.page
            val initialInfo = opened.getPageInfo(initialPage)
            view.addOnFirstContentLoadListener { view.showPage(initialInfo); ready = true; state.viewport(initialPage, view.zoom) }
            view.pdfDocument = opened
            val pdf = opened
            coroutineScope {
                launch { state.commands.filter { it?.second !is PdfCommand.Search }.collectLatest { request ->
                    when (val command = request?.second) {
                        is PdfCommand.Page -> view.showPage(pdf.getPageInfo(command.index))
                        is PdfCommand.Zoom -> view.zoom = (view.zoom * command.factor).coerceIn(view.minZoom, view.maxZoom)
                        else -> Unit
                    }
                } }
                launch { state.commands.filter { it?.second is PdfCommand.Search }.collectLatest { request ->
                    val command = request?.second as? PdfCommand.Search ?: return@collectLatest
                    state.searching()
                    try {
                        val hits = mutableListOf<PdfSearchHit>()
                        if (command.query.isNotBlank() && state.state.value.textAvailable) {
                            for (page in 0 until pdf.pageCount) {
                                val matches = pdf.searchDocument(command.query, page..page)[page].orEmpty()
                                hits += matches.map { match -> PdfSearchHit(page, match.bounds.map { PdfRegion(page, it.left, it.top, it.right, it.bottom) }) }
                            }
                        }
                        view.setHighlights(hits.flatMap { it.regions }.map { Highlight(PdfRect(it.page, it.left, it.top, it.right, it.bottom), 0x66FFD54F) })
                        state.found(hits)
                        hits.firstOrNull()?.let { view.showPage(pdf.getPageInfo(it.page)) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { state.failed(error.message ?: "This PDF could not be searched.") }
                } }
                awaitCancellation()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.failed(error.message ?: "This PDF could not be opened.")
        } finally {
            ready = false
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
        }
    }
    DisposableEffect(view) {
        val viewport = object : PdfView.OnViewportChangedListener {
            override fun onViewportChanged(firstVisiblePage: Int, visiblePagesCount: Int, pageLocations: SparseArray<RectF>, zoomLevel: Float) {
                if (!ready) return
                // A sliver of the previous page must not change the page we bookmark or resume.
                val current = (0 until pageLocations.size()).maxByOrNull { index ->
                    val bounds = pageLocations.valueAt(index)
                    val width = (minOf(bounds.right, view.width.toFloat()) - maxOf(bounds.left, 0f)).coerceAtLeast(0f)
                    val height = (minOf(bounds.bottom, view.height.toFloat()) - maxOf(bounds.top, 0f)).coerceAtLeast(0f)
                    width * height
                }?.let(pageLocations::keyAt) ?: firstVisiblePage
                state.viewport(current, zoomLevel)
            }
        }
        val selection = object : PdfView.OnSelectionChangedListener {
            override fun onSelectionChanged(newSelection: Selection?) {
                (newSelection as? TextSelection)?.let { selected ->
                    state.selected(PdfSelection(selected.text.toString(), selected.bounds.map { PdfRegion(it.pageNum, it.left, it.top, it.right, it.bottom) }))
                }
            }
        }
        view.addOnViewportChangedListener(viewport)
        view.addOnSelectionChangedListener(selection)
        onDispose {
            view.removeOnViewportChangedListener(viewport)
            view.removeOnSelectionChangedListener(selection)
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

private fun PdfView.showPage(info: PdfDocument.PageInfo) {
    scrollToPage(info.pageNum)
    // AndroidX centres the whole page. Start at its top so short viewports still show the heading.
    scrollToPosition(PdfPoint(info.pageNum, info.width / 2f, height / (2f * zoom)))
}
