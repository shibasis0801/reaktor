package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.listSource
import dev.shibasis.reaktor.surface.treeSource
import dev.shibasis.reaktor.surface.compose.AutomationScope
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.ColumnWidth
import dev.shibasis.reaktor.surface.compose.DataTable
import dev.shibasis.reaktor.surface.compose.TableColumn
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.surface.compose.Tree
import dev.shibasis.reaktor.surface.compose.foundation.Box
import dev.shibasis.reaktor.surface.compose.foundation.Column
import dev.shibasis.reaktor.surface.compose.foundation.Image
import dev.shibasis.reaktor.surface.compose.foundation.Row
import dev.shibasis.reaktor.ui.code.CodeLanguage
import dev.shibasis.reaktor.ui.code.CodeViewer
import dev.shibasis.reaktor.ui.media.MediaKind
import dev.shibasis.reaktor.ui.media.MediaSource
import dev.shibasis.reaktor.ui.media.csvRecords
import dev.shibasis.reaktor.ui.media.mediaHex
import dev.shibasis.reaktor.ui.media.mediaHtml
import dev.shibasis.reaktor.ui.media.mediaKind
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.web.ReaktorWebView
import dev.shibasis.reaktor.ui.web.rememberWebViewState
import dev.shibasis.reaktor.web.WebContent
import dev.shibasis.reaktor.web.WebRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.Base64
import kotlin.math.roundToInt

@Composable
fun MediaViewer(runtime: WebRuntime, source: MediaSource, contentType: String, modifier: Modifier = Modifier, tag: String = "media-viewer") {
    val kind = remember(contentType) { mediaKind(contentType) }
    val native = kind in setOf(MediaKind.Audio, MediaKind.Video, MediaKind.Pdf)
    var bytes by remember(source) { mutableStateOf((source as? MediaSource.Bytes)?.bytes) }
    var failure by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(runtime, source, native) {
        if (native || source is MediaSource.Bytes) return@LaunchedEffect
        val read = runtime.coroutineScope.async(Dispatchers.IO) {
            URI((source as MediaSource.Url).url).toURL().openConnection().apply { connectTimeout = 15_000; readTimeout = 15_000 }.getInputStream().use { it.readBytes() }
        }
        try { bytes = read.await() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { failure = error.message ?: "Could not read media" }
        finally { read.cancel() }
    }
    Column(modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
        Text("${contentType.ifBlank { "Unknown content type" }} · ${bytes?.let { "${it.size} bytes" } ?: "Remote media"}", Modifier.testTag("$tag-info"), role = Type.Label, ink = Ink.Muted)
        when {
            failure != null -> Text("Could not read media: $failure", Modifier.testTag("$tag-error"), ink = Ink.Danger, lines = Int.MAX_VALUE)
            native -> NativeMedia(runtime, source, contentType, kind, Modifier.weight(1f).fillMaxWidth(), tag)
            bytes == null -> Text("Reading media…", Modifier.testTag("$tag-loading"), ink = Ink.Muted)
            else -> bytes?.let { ByteMedia(it, kind, Modifier.weight(1f).fillMaxWidth(), tag) }
        }
    }
}

@Composable
private fun NativeMedia(runtime: WebRuntime, source: MediaSource, contentType: String, kind: MediaKind, modifier: Modifier, tag: String) {
    val location = remember(source, contentType) {
        when (source) {
            is MediaSource.Url -> source.url
            is MediaSource.Bytes -> "data:${contentType.substringBefore(';')};base64,${Base64.getEncoder().encodeToString(source.bytes)}"
        }
    }
    val content = remember(kind, location) { WebContent.Html(mediaHtml(kind, location)) }
    val state = rememberWebViewState()
    val error by state.failure.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
        Text(if (kind == MediaKind.Pdf) "PDF · native page controls" else "${kind.name} · play and scrub with native controls", Modifier.testTag("$tag-controls"), role = Type.Label, ink = Ink.Muted)
        error?.let { Text("${kind.name} viewer unavailable: ${it.message}", Modifier.testTag("$tag-error"), ink = Ink.Warn, lines = Int.MAX_VALUE) }
        ReaktorWebView(runtime, content, Modifier.weight(1f).fillMaxWidth(), state)
    }
}

@Composable
private fun ByteMedia(bytes: ByteArray, kind: MediaKind, modifier: Modifier, tag: String) {
    val parsed = remember(bytes, kind) { runCatching {
        when (kind) {
            MediaKind.Image -> org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
            MediaKind.Json -> Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            MediaKind.Csv -> csvRecords(bytes.decodeToString(throwOnInvalidSequence = true))
            MediaKind.Text -> bytes.decodeToString(throwOnInvalidSequence = true)
            else -> mediaHex(bytes)
        }
    } }
    val content = parsed.getOrNull()
    if (parsed.isFailure) {
        Text("Could not decode ${kind.name.lowercase()}: ${parsed.exceptionOrNull()?.message}", modifier.testTag("$tag-error"), ink = Ink.Warn, lines = Int.MAX_VALUE)
        return
    }
    when (kind) {
        MediaKind.Image -> MediaImage(content as androidx.compose.ui.graphics.ImageBitmap, modifier, tag)
        MediaKind.Json -> MediaJson(content as JsonElement, modifier, tag)
        MediaKind.Csv -> { @Suppress("UNCHECKED_CAST") val rows = content as List<List<String>>; MediaCsv(rows, modifier, tag) }
        else -> Column(modifier) {
            if (kind == MediaKind.Unsupported) Text("Unsupported content type · showing binary bytes", Modifier.testTag("$tag-unsupported"), role = Type.Label, ink = Ink.Warn)
            CodeViewer(content as String, Modifier.weight(1f).fillMaxWidth(), language = if (kind == MediaKind.Text) CodeLanguage.sniff(content) else CodeLanguage.Plain, tag = "$tag-code")
        }
    }
}

@Composable
private fun MediaImage(bitmap: androidx.compose.ui.graphics.ImageBitmap, modifier: Modifier, tag: String) {
    var zoom by remember(bitmap) { mutableStateOf(1f) }
    var fit by remember(bitmap) { mutableStateOf(true) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1)) {
            Button({ zoom = (zoom / 1.25f).coerceAtLeast(.125f) }, Modifier.testTag("$tag-zoom-out")) { Text("−") }
            Button({ zoom = (zoom * 1.25f).coerceAtMost(8f) }, Modifier.testTag("$tag-zoom-in")) { Text("+") }
            Button({ fit = true; zoom = 1f }, Modifier.testTag("$tag-fit")) { Text("Fit") }
            Button({ fit = false; zoom = 1f }, Modifier.testTag("$tag-pixels")) { Text("1:1") }
        }
        Text("${bitmap.width} × ${bitmap.height} px · ${(zoom * 100).roundToInt()}%${if (fit) " of fit" else ""}", Modifier.testTag("$tag-image-size"), role = Type.Label, ink = Ink.Muted)
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Image(bitmap, "Media image", Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom), contentScale = if (fit) ContentScale.Fit else ContentScale.None)
        }
    }
}

private data class JsonBranch(val path: String, val name: String, val value: JsonElement) {
    fun children(): List<JsonBranch> = when (value) {
        is JsonObject -> value.map { (key, child) -> JsonBranch("$path/${key.replace("~", "~0").replace("/", "~1")}", key, child) }
        is JsonArray -> value.mapIndexed { index, child -> JsonBranch("$path/$index", index.toString(), child) }
        else -> emptyList()
    }
    val label: String get() = "$name: " + when (value) {
        is JsonObject -> "{${value.size} fields}"
        is JsonArray -> "[${value.size} items]"
        else -> value.toString()
    }
}

@Composable
private fun MediaJson(value: JsonElement, modifier: Modifier, tag: String) {
    var open by remember(value) { mutableStateOf(setOf("$")) }
    val source = remember(value, open) { treeSource(listOf(JsonBranch("$", "document", value)), { it.path }, { it.children() }, open, text = { it.label }) }
    AutomationScope(tag) {
        Tree(source, emptySet(), {}, { key, expanded -> open = if (expanded) open + key else open - key }, modifier) { TreeItem(it.label) }
    }
}

@Composable
private fun MediaCsv(records: List<List<String>>, modifier: Modifier, tag: String) {
    val rows = remember(records) { records.drop(1).mapIndexed { index, values -> index to values } }
    val columns = remember(records) {
        (0 until (records.maxOfOrNull { it.size } ?: 0)).map { index ->
            TableColumn<Pair<Int, List<String>>>(index.toString(), ColumnWidth.Share(1f),
                header = { Text(records.firstOrNull()?.getOrNull(index) ?: "Column ${index + 1}") },
                cell = { Text(it.second.getOrNull(index).orEmpty(), Modifier.padding(horizontal = MachineSignal.Space.s2)) })
        }
    }
    AutomationScope(tag) { DataTable(listSource(rows, { it.first.toString() }), columns, emptySet(), {}, modifier, empty = { Text("No CSV records") }) }
}
