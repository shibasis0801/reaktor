@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shibasis.reaktor.media.pdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSCaseInsensitiveSearch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFPage
import platform.PDFKit.PDFSelection
import platform.PDFKit.PDFView
import platform.PDFKit.PDFViewPageChangedNotification
import platform.PDFKit.PDFViewScaleChangedNotification
import platform.PDFKit.PDFViewSelectionChangedNotification
import platform.PDFKit.kPDFDisplayBoxCropBox
import platform.PDFKit.kPDFDisplayDirectionVertical
import platform.PDFKit.kPDFDisplaySinglePageContinuous
import platform.UIKit.UIColor

@Composable
actual fun PdfReader(path: String, state: PdfReaderState, modifier: Modifier) {
    val view = remember(path, state) { ReaderPdfView().apply {
        autoScales = true
        displayMode = kPDFDisplaySinglePageContinuous
        displayDirection = kPDFDisplayDirectionVertical
    } }
    var ready by remember(path, state) { mutableStateOf(false) }
    LaunchedEffect(path, state) {
        try {
            val document = withContext(Dispatchers.Default) { PDFDocument(uRL = NSURL.fileURLWithPath(path)) }
                ?: error("This PDF could not be opened.")
            if (document.isLocked()) error("This PDF is locked. Open an unlocked copy.")
            state.loaded(document.pageCount.toInt(), true)
            val initialPage = state.state.value.page
            val laidOut = CompletableDeferred<Unit>()
            view.install(document, initialPage) {
                ready = true
                state.viewport(initialPage, view.scaleFactor.toFloat())
                laidOut.complete(Unit)
            }
            // PDFKit ignores goToPage on a zero-sized, unattached view. Restore after
            // native layout, before accepting its initial page/scale notifications.
            laidOut.await()
            coroutineScope {
                launch {
                    state.commands.filter { it?.second !is PdfCommand.Search }.collectLatest { request ->
                        when (val command = request?.second) {
                            is PdfCommand.Page -> document.pageAtIndex(command.index.toULong())?.let(view::goToPage)
                            is PdfCommand.Zoom -> view.scaleFactor = (view.scaleFactor * command.factor)
                                .coerceIn(view.minScaleFactor, view.maxScaleFactor)
                            else -> Unit
                        }
                    }
                }
                launch {
                    state.commands.filter { it?.second is PdfCommand.Search }.collectLatest { request ->
                        val command = request?.second as? PdfCommand.Search ?: return@collectLatest
                        state.searching()
                        try {
                            val matches = if (command.query.isBlank()) emptyList() else withContext(Dispatchers.Default) {
                                document.findString(command.query, withOptions = NSCaseInsensitiveSearch)
                                    .filterIsInstance<PDFSelection>()
                            }
                            matches.forEach { it.color = UIColor(red = 1.0, green = 0.83, blue = 0.3, alpha = 0.4) }
                            view.highlightedSelections = matches
                            state.found(matches.mapNotNull { selection ->
                                val regions = selection.regions(document)
                                regions.firstOrNull()?.let { PdfSearchHit(it.page, regions) }
                            })
                            matches.firstOrNull()?.let { view.goToSelection(it) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            state.failed(error.message ?: "This PDF could not be searched.")
                        }
                    }
                }
                awaitCancellation()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { state.failed(error.message ?: "This PDF could not be opened.") }
        finally { ready = false; view.reset() }
    }
    DisposableEffect(view) {
        val notifications = NSNotificationCenter.defaultCenter
        fun viewport() {
            if (!ready) return
            val document = view.document ?: return
            val page = view.currentPage ?: return
            state.viewport(document.indexForPage(page).toInt(), view.scaleFactor.toFloat())
        }
        val observers = listOf(
            notifications.addObserverForName(PDFViewPageChangedNotification, view, NSOperationQueue.mainQueue) { viewport() },
            notifications.addObserverForName(PDFViewScaleChangedNotification, view, NSOperationQueue.mainQueue) { viewport() },
            notifications.addObserverForName(PDFViewSelectionChangedNotification, view, NSOperationQueue.mainQueue) {
                val document = view.document
                val selection = view.currentSelection
                if (document != null && selection != null) state.selected(PdfSelection(selection.string.orEmpty(), selection.regions(document)))
            },
        )
        onDispose { observers.forEach(notifications::removeObserver); view.reset() }
    }
    UIKitView(factory = { view }, modifier = modifier,
        properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative, isNativeAccessibilityEnabled = true))
}

private class ReaderPdfView : PDFView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private var restore: Pair<PDFPage, () -> Unit>? = null

    fun install(document: PDFDocument, page: Int, ready: () -> Unit) {
        restore = null
        this.document = document
        restore = document.pageAtIndex(page.toULong())?.let { it to ready }
        setNeedsLayout()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val pending = restore ?: return
        if (window == null || !bounds.useContents { size.width > 0 && size.height > 0 }) return
        restore = null
        layoutDocumentView()
        goToPage(pending.first)
        pending.second()
    }

    fun reset() { restore = null; document = null }
}

private fun PDFSelection.regions(document: PDFDocument): List<PdfRegion> = selectionsByLine().filterIsInstance<PDFSelection>().flatMap { line ->
    line.pages.filterIsInstance<PDFPage>().map { page ->
        val pageTop = page.boundsForBox(kPDFDisplayBoxCropBox).useContents { origin.y + size.height }
        line.boundsForPage(page).useContents {
            PdfRegion(document.indexForPage(page).toInt(), origin.x.toFloat(), (pageTop - origin.y - size.height).toFloat(),
                (origin.x + size.width).toFloat(), (pageTop - origin.y).toFloat())
        }
    }
}
