package dev.shibasis.reaktor.blueprint.product

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.blueprint.Blueprint
import dev.shibasis.reaktor.blueprint.BlueprintCardLook
import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintFace
import dev.shibasis.reaktor.blueprint.BlueprintFrameLook
import dev.shibasis.reaktor.blueprint.BlueprintGraph
import dev.shibasis.reaktor.blueprint.BlueprintMark
import dev.shibasis.reaktor.blueprint.BlueprintPinLook
import dev.shibasis.reaktor.blueprint.Pin
import dev.shibasis.reaktor.blueprint.canvas.GraphCanvasState
import dev.shibasis.reaktor.blueprint.editor.Selection
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalMode
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant

/** Product-owned labels and tones; the graph only arranges and paints them. */
data class SurfaceGraphBadge(val text: String, val background: Color, val foreground: Color, val rounded: Boolean = false)
data class SurfaceGraphRow(val id: String, val title: String, val key: String = id, val aliases: List<String> = emptyList(),
    val badges: List<SurfaceGraphBadge> = emptyList(), val marker: Color? = null, val background: Color? = null, val completed: Boolean = false,
    val input: Color? = null, val output: Color? = null)
data class SurfaceGraphNode(val id: String, val title: String, val subtitle: String, val scope: String = "", val color: Color? = null,
    val rows: List<SurfaceGraphRow> = emptyList(), val folded: Int = 0, val owner: String = id, val farTitle: String = title, val foldedLabel: String = "units", val symbol: String = "book", val lane: Int = 0,
    val badges: List<SurfaceGraphBadge> = emptyList(), val progress: Float = 0f, val activity: Color? = null, val counts: List<SurfaceGraphCount> = emptyList(),
    val opens: Boolean = false)
fun SurfaceGraphNode.pins() = rows.mapIndexed { i, row -> Pin(row.key, "", false, BlueprintEngine.HeaderHeight + i * BlueprintEngine.RowHeight + BlueprintEngine.RowHeight / 2, PortWiring.Connected, row.id, row.aliases) }
enum class SurfaceGraphMark { Done, Session, Note }
data class SurfaceGraphCount(val text: String, val mark: SurfaceGraphMark? = null)
data class SurfaceGraphGroup(val key: String, val title: String, val subtitle: String = "", val color: Color? = null, val parent: String? = null, val owner: String? = null, val lanes: Boolean = parent != null, val loose: Boolean = false, val muted: Boolean = false, val opaque: Boolean = false)

data class SurfaceGraphLink(val id: String, val from: String, val to: String, val label: String = "", val proposed: Boolean = false, val color: Color? = null)

data class SurfaceGraphAppearance(
    val canvas: Color,
    val card: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val selected: Color,
    val width: Double = 220.0,
    val height: Double = 88.0,
    val fontFamily: FontFamily? = null,
    val titleFontFamily: FontFamily? = null,
    val grid: Boolean = false,
    val textPlatformStyle: androidx.compose.ui.text.PlatformTextStyle? = null,
)

fun surfaceGraphPositions(nodes: List<SurfaceGraphNode>, width: Double = 220.0, height: Double = 88.0): Map<String, Pair<Double, Double>> {
    val out = linkedMapOf<String, Pair<Double, Double>>()
    var rowOffset = 0
    for ((_, group) in nodes.groupBy { it.scope }.entries.sortedBy { it.key }) {
        group.sortedBy { it.id }.forEachIndexed { index, node -> out[node.id] = (index % 3) * (width + 64) to (rowOffset + index / 3) * (height + 68) }
        rowOffset += (group.size + 2) / 3 + 1
    }
    return out
}

@Composable
fun SurfaceGraphCanvas(
    nodes: List<SurfaceGraphNode>,
    links: List<SurfaceGraphLink>,
    selectedId: String?,
    appearance: SurfaceGraphAppearance,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    sceneKey: String = "",
) {
    val canvas = remember(sceneKey) { GraphCanvasState() }
    val model = remember(sceneKey, nodes, links, appearance.width, appearance.height) { SurfaceGraphSimple(nodes, links, appearance) }
    val select by rememberUpdatedState(onSelect)
    SideEffect {
        model.editor.onSelect = { id -> id?.let(select) }
        if (model.editor.selection.nodes.singleOrNull() != selectedId) model.editor.select(Selection(nodes = selectedId?.let(::setOf).orEmpty()))
    }
    MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact,
        mode = if (appearance.canvas.luminance() > .5f) MachineSignalMode.Light else MachineSignalMode.Dark) {
        BlueprintGraph(model.editor, canvas,
            cardLook = { node -> model.look(node.id, appearance) },
            groupLook = { BlueprintFrameLook(SurfaceGraphSymbol, "") },
            linkStyle = { link -> model.link(link.id)?.let { Blueprint.link(it.color ?: appearance.selected, false, false, dash = if (it.proposed) 6f to 4f else null) } },
            linkLabel = { model.link(it.id)?.label?.takeIf(String::isNotBlank) },
            modifier = modifier,
            denseOverview = true)
    }
}

internal val SurfaceGraphSymbol = ImageVector.Builder("Surface document", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f) {
        moveTo(4f, 3f); lineTo(20f, 3f); lineTo(20f, 21f); lineTo(4f, 21f); close()
        moveTo(8f, 3f); lineTo(8f, 21f)
        moveTo(11f, 8f); lineTo(17f, 8f)
        moveTo(11f, 12f); lineTo(17f, 12f)
    }
}.build()

internal fun SurfaceGraphSimple.look(id: String, appearance: SurfaceGraphAppearance): BlueprintCardLook {
    val node = nodes.getValue(id)
    val card = editor.layout.cards.getValue(id)
    val tone = node.color ?: appearance.selected
    return BlueprintCardLook(tone, SurfaceGraphSymbol, node.title, node.subtitle,
        tag = "surface-card:$id",
        action = "Select ${node.title}",
        folded = node.folded.takeIf { it > 0 }?.let { "$it ${node.foldedLabel}" },
        marks = node.badges.map { BlueprintMark(it.text, it.background, BlueprintMark.Kind.Badge) } +
            node.counts.map { BlueprintMark(it.text, tone) },
        pins = card.pins.map { pin -> document.row(id, pin.key)?.let { row ->
            BlueprintPinLook(if (pin.provides) row.output ?: tone else row.input ?: tone, row.title, appearance.text, row.completed,
                marks = row.badges.map { BlueprintMark(it.text, it.background, BlueprintMark.Kind.Badge) })
        } },
        face = if (node.rows.isEmpty()) BlueprintFace.Prose else BlueprintFace.Code,
        progress = node.progress.takeIf { it > 0f })
}
