package dev.shibasis.reaktor.surface.compose.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.VerticalAlignmentLine
import dev.shibasis.reaktor.surface.compose.LocalSurfaceStudio
import dev.shibasis.reaktor.surface.compose.Spread
import dev.shibasis.reaktor.surface.compose.StudioValues
import dev.shibasis.reaktor.surface.compose.SurfaceCall
import dev.shibasis.reaktor.surface.compose.SurfaceEdits
import dev.shibasis.reaktor.surface.compose.SurfaceField
import dev.shibasis.reaktor.surface.compose.SurfaceFlow
import dev.shibasis.reaktor.surface.compose.choice
import dev.shibasis.reaktor.surface.compose.count
import dev.shibasis.reaktor.surface.compose.flag
import dev.shibasis.reaktor.surface.compose.size
import androidx.compose.foundation.layout.Box as FoundationBox
import androidx.compose.foundation.layout.BoxWithConstraints as FoundationBoxWithConstraints
import androidx.compose.foundation.layout.Column as FoundationColumn
import androidx.compose.foundation.layout.FlowRow as FoundationFlowRow
import androidx.compose.foundation.layout.Row as FoundationRow
import androidx.compose.foundation.layout.Spacer as FoundationSpacer

@Composable
@NonRestartableComposable
fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationColumn(modifier, verticalArrangement, horizontalAlignment, content)
    else {
        val spread = StudioValues.spread(verticalArrangement)
        studio.Element(lineCall("Column", modifier, spread, StudioValues.cross(horizontalAlignment), SurfaceFlow.Vertical)) { edits ->
            val inner = edits.scoped("content", content)
            val chosen = edits.spread(spread)
            val alignment = edits.value("alignment")?.let(StudioValues::horizontalOf) ?: horizontalAlignment
            when (edits.value("layout")) {
                "Row" -> FoundationRow(edits.modifier, StudioValues.horizontal(chosen.name, chosen.spacing), StudioValues.crossed(alignment)) { ColumnInRow(this).inner() }
                "Box" -> FoundationBox(edits.modifier, StudioValues.boxed(alignment)) { ColumnInBox(this).inner() }
                else -> FoundationColumn(edits.modifier, if (edits.spreadEdited()) StudioValues.vertical(chosen.name, chosen.spacing) else verticalArrangement, alignment) { inner() }
            }
        }
    }
}

@Composable
@NonRestartableComposable
fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationRow(modifier, horizontalArrangement, verticalAlignment, content)
    else {
        val spread = StudioValues.spread(horizontalArrangement)
        studio.Element(lineCall("Row", modifier, spread, StudioValues.cross(verticalAlignment), SurfaceFlow.Horizontal)) { edits ->
            val inner = edits.scoped("content", content)
            val chosen = edits.spread(spread)
            val alignment = edits.value("alignment")?.let(StudioValues::verticalOf) ?: verticalAlignment
            when (edits.value("layout")) {
                "Column" -> FoundationColumn(edits.modifier, StudioValues.vertical(chosen.name, chosen.spacing), StudioValues.crossed(alignment)) { RowInColumn(this).inner() }
                "Box" -> FoundationBox(edits.modifier, StudioValues.boxed(alignment)) { RowInBox(this).inner() }
                else -> FoundationRow(edits.modifier, if (edits.spreadEdited()) StudioValues.horizontal(chosen.name, chosen.spacing) else horizontalArrangement, alignment) { inner() }
            }
        }
    }
}

@Composable
@NonRestartableComposable
fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationBox(modifier, contentAlignment, propagateMinConstraints, content)
    else studio.Element(SurfaceCall("Box", modifier, listOf(
        SurfaceField.choice("layout", "Box", StudioValues.Layouts),
        SurfaceField.choice("contentAlignment", contentAlignment, StudioValues.Alignments),
        SurfaceField.flag("propagateMinConstraints", propagateMinConstraints),
    ), slots = listOf("content"), flow = SurfaceFlow.Layered)) { edits ->
        val inner = edits.scoped("content", content)
        val alignment = edits.choice("contentAlignment", contentAlignment, StudioValues.Alignments)
        when (edits.value("layout")) {
            "Column" -> FoundationColumn(edits.modifier, horizontalAlignment = alignment.horizontal()) { BoxInColumn(this).inner() }
            "Row" -> FoundationRow(edits.modifier, verticalAlignment = alignment.vertical()) { BoxInRow(this).inner() }
            else -> FoundationBox(edits.modifier, alignment, edits.flag("propagateMinConstraints", propagateMinConstraints)) { inner() }
        }
    }
}

@Composable
@NonRestartableComposable
fun Box(modifier: Modifier) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationBox(modifier)
    else studio.Element(SurfaceCall("Box", modifier)) { edits -> FoundationBox(edits.modifier) }
}

@Composable
@NonRestartableComposable
fun Spacer(modifier: Modifier) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationSpacer(modifier)
    else studio.Element(SurfaceCall("Spacer", modifier)) { edits -> FoundationSpacer(edits.modifier) }
}

@Composable
@NonRestartableComposable
fun BoxWithConstraints(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationBoxWithConstraints(modifier, contentAlignment, propagateMinConstraints, content)
    else studio.Element(SurfaceCall("BoxWithConstraints", modifier, listOf(
        SurfaceField.choice("contentAlignment", contentAlignment, StudioValues.Alignments),
        SurfaceField.flag("propagateMinConstraints", propagateMinConstraints),
    ), slots = listOf("content"), flow = SurfaceFlow.Layered)) { edits ->
        val inner = edits.scoped("content", content)
        FoundationBoxWithConstraints(edits.modifier, edits.choice("contentAlignment", contentAlignment, StudioValues.Alignments),
            edits.flag("propagateMinConstraints", propagateMinConstraints)) { inner() }
    }
}

@Composable
@NonRestartableComposable
fun FlowRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    itemVerticalAlignment: Alignment.Vertical = Alignment.Top,
    maxItemsInEachRow: Int = Int.MAX_VALUE,
    maxLines: Int = Int.MAX_VALUE,
    content: @Composable FlowRowScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationFlowRow(modifier, horizontalArrangement, verticalArrangement, itemVerticalAlignment, maxItemsInEachRow, maxLines, content)
    else {
        val spread = StudioValues.spread(horizontalArrangement)
        val lines = StudioValues.spread(verticalArrangement)
        studio.Element(SurfaceCall("FlowRow", modifier, listOf(
            SurfaceField.choice("arrangement", spread.name, StudioValues.Spreads),
            SurfaceField.size("spacing", spread.spacing),
            SurfaceField.size("lineSpacing", lines.spacing),
            SurfaceField.choice("alignment", StudioValues.cross(itemVerticalAlignment), StudioValues.Cross),
            SurfaceField.count("maxItemsInEachRow", maxItemsInEachRow),
            SurfaceField.count("maxLines", maxLines),
        ), slots = listOf("content"), flow = SurfaceFlow.Wrap)) { edits ->
            val inner = edits.scoped("content", content)
            val chosen = edits.spread(spread)
            FoundationFlowRow(
                edits.modifier,
                if (edits.spreadEdited()) StudioValues.horizontal(chosen.name, chosen.spacing) else horizontalArrangement,
                if (edits.value("lineSpacing") != null) StudioValues.vertical(lines.name, edits.size("lineSpacing", lines.spacing)) else verticalArrangement,
                edits.value("alignment")?.let(StudioValues::verticalOf) ?: itemVerticalAlignment,
                edits.count("maxItemsInEachRow", maxItemsInEachRow),
                edits.count("maxLines", maxLines),
            ) { inner() }
        }
    }
}

internal fun lineCall(kind: String, modifier: Modifier, spread: Spread, cross: String, flow: SurfaceFlow) = SurfaceCall(kind, modifier, listOf(
    SurfaceField.choice("layout", kind, StudioValues.Layouts),
    SurfaceField.choice("arrangement", spread.name, StudioValues.Spreads),
    SurfaceField.size("spacing", spread.spacing),
    SurfaceField.choice("alignment", cross, StudioValues.Cross),
), slots = listOf("content"), flow = flow)

internal fun SurfaceEdits.spread(original: Spread): Spread = Spread(value("arrangement") ?: original.name, size("spacing", original.spacing))

internal fun SurfaceEdits.spreadEdited(): Boolean = value("arrangement") != null || value("spacing") != null

private fun Alignment.horizontal(): Alignment.Horizontal = (this as? BiasAlignment)?.let { BiasAlignment.Horizontal(it.horizontalBias) } ?: Alignment.Start

private fun Alignment.vertical(): Alignment.Vertical = (this as? BiasAlignment)?.let { BiasAlignment.Vertical(it.verticalBias) } ?: Alignment.Top

private fun RowScope.weighted(modifier: Modifier, weight: Float, fill: Boolean): Modifier = modifier.weight(weight, fill)

private fun RowScope.aligned(modifier: Modifier, alignment: Alignment.Vertical): Modifier = modifier.align(alignment)

private fun RowScope.measuredBy(modifier: Modifier, block: (Measured) -> Int): Modifier = modifier.alignBy(block)

private fun ColumnScope.weighted(modifier: Modifier, weight: Float, fill: Boolean): Modifier = modifier.weight(weight, fill)

private fun ColumnScope.aligned(modifier: Modifier, alignment: Alignment.Horizontal): Modifier = modifier.align(alignment)

private fun ColumnScope.measuredBy(modifier: Modifier, block: (Measured) -> Int): Modifier = modifier.alignBy(block)

private fun BoxScope.aligned(modifier: Modifier, alignment: Alignment): Modifier = modifier.align(alignment)

private class ColumnInRow(private val row: RowScope) : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = row.weighted(this, weight, fill)
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = row.aligned(this, StudioValues.crossed(alignment))
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = row.measuredBy(this, alignmentLineBlock)
}

private class ColumnInBox(private val box: BoxScope) : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = box.aligned(this, StudioValues.boxed(alignment))
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

private class RowInColumn(private val column: ColumnScope) : RowScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = column.weighted(this, weight, fill)
    override fun Modifier.align(alignment: Alignment.Vertical): Modifier = column.aligned(this, StudioValues.crossed(alignment))
    override fun Modifier.alignBy(alignmentLine: HorizontalAlignmentLine): Modifier = this
    override fun Modifier.alignByBaseline(): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = column.measuredBy(this, alignmentLineBlock)
}

private class RowInBox(private val box: BoxScope) : RowScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this
    override fun Modifier.align(alignment: Alignment.Vertical): Modifier = box.aligned(this, StudioValues.boxed(alignment))
    override fun Modifier.alignBy(alignmentLine: HorizontalAlignmentLine): Modifier = this
    override fun Modifier.alignByBaseline(): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

private class BoxInColumn(private val column: ColumnScope) : BoxScope {
    override fun Modifier.align(alignment: Alignment): Modifier = column.aligned(this, alignment.horizontal())
    override fun Modifier.matchParentSize(): Modifier = fillMaxWidth()
}

private class BoxInRow(private val row: RowScope) : BoxScope {
    override fun Modifier.align(alignment: Alignment): Modifier = row.aligned(this, alignment.vertical())
    override fun Modifier.matchParentSize(): Modifier = fillMaxHeight()
}
