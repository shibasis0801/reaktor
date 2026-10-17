package dev.shibasis.reaktor.media.pdf

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PdfRegion(val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class PdfSelection(val text: String, val regions: List<PdfRegion>) {
    val page: Int get() = regions.first().page
}
data class PdfSearchHit(val page: Int, val regions: List<PdfRegion>)
data class PdfReading(
    val page: Int = 0,
    val pageCount: Int = 0,
    val zoom: Float = 1f,
    val textAvailable: Boolean = false,
    val searching: Boolean = false,
    val matches: List<PdfSearchHit> = emptyList(),
    val selection: PdfSelection? = null,
    val problem: String? = null,
)

sealed interface PdfCommand {
    data class Page(val index: Int) : PdfCommand
    data class Zoom(val factor: Float) : PdfCommand
    data class Search(val query: String) : PdfCommand
}

class PdfReaderState(initialPage: Int = 0) {
    private val reading = MutableStateFlow(PdfReading(page = initialPage.coerceAtLeast(0)))
    val state: StateFlow<PdfReading> = reading.asStateFlow()
    private val requested = MutableStateFlow<Pair<Long, PdfCommand>?>(null)
    val commands: StateFlow<Pair<Long, PdfCommand>?> = requested.asStateFlow()
    private var sequence = 0L

    private fun request(command: PdfCommand) { requested.value = ++sequence to command }
    fun goToPage(index: Int) {
        val count = reading.value.pageCount
        if (index !in 0 until count) return
        reading.update { it.copy(selection = null) }
        request(PdfCommand.Page(index))
    }
    fun zoomBy(factor: Float) {
        if (factor.isFinite() && factor > 0f) request(PdfCommand.Zoom(factor))
    }
    fun search(query: String) { request(PdfCommand.Search(query.trim())) }
    fun loaded(pageCount: Int, textAvailable: Boolean) {
        require(pageCount > 0)
        reading.update { it.copy(pageCount = pageCount, page = it.page.coerceIn(0, pageCount - 1), textAvailable = textAvailable, problem = null) }
    }
    fun viewport(page: Int, zoom: Float) {
        reading.update { if (page in 0 until it.pageCount && zoom.isFinite() && zoom > 0) it.copy(page = page, zoom = zoom) else it }
    }
    fun selected(selection: PdfSelection?) {
        if (selection == null || selection.text.isBlank() || selection.regions.isEmpty()) return
        if (selection.regions.any { it.page !in 0 until reading.value.pageCount }) return
        reading.update { it.copy(selection = selection) }
    }
    fun searching() { reading.update { it.copy(searching = true, matches = emptyList(), problem = null) } }
    fun found(matches: List<PdfSearchHit>) { reading.update { it.copy(searching = false, matches = matches) } }
    fun failed(message: String) { reading.update { it.copy(searching = false, problem = message) } }
}

@Composable
expect fun PdfReader(path: String, state: PdfReaderState, modifier: Modifier = Modifier)
