package dev.shibasis.reaktor.surface.compose.foundation

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.compose.LocalSurfaceStudio
import dev.shibasis.reaktor.surface.compose.StudioValues
import dev.shibasis.reaktor.surface.compose.SurfaceCall
import dev.shibasis.reaktor.surface.compose.SurfaceEdits
import dev.shibasis.reaktor.surface.compose.SurfaceField
import dev.shibasis.reaktor.surface.compose.SurfaceFlow
import dev.shibasis.reaktor.surface.compose.flag
import dev.shibasis.reaktor.surface.compose.size
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn as FoundationLazyColumn
import androidx.compose.foundation.lazy.LazyRow as FoundationLazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid as FoundationLazyVerticalGrid

private const val Appended = "surface-studio-appended"

@Composable
@NonRestartableComposable
fun LazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    content: LazyListScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationLazyColumn(modifier, state, contentPadding, reverseLayout, verticalArrangement, horizontalAlignment, flingBehavior, userScrollEnabled, overscrollEffect, content)
    else {
        val spread = StudioValues.spread(verticalArrangement)
        studio.Element(listCall("LazyColumn", modifier, spread.name, spread.spacing, StudioValues.cross(horizontalAlignment), contentPadding, reverseLayout, userScrollEnabled, SurfaceFlow.Vertical)) { edits ->
            val chosen = edits.spread(spread)
            val trailing = edits.appended("content")
            FoundationLazyColumn(
                edits.modifier, state, edits.padding(contentPadding), edits.flag("reverseLayout", reverseLayout),
                if (edits.spreadEdited()) StudioValues.vertical(chosen.name, chosen.spacing) else verticalArrangement,
                edits.value("alignment")?.let(StudioValues::horizontalOf) ?: horizontalAlignment,
                flingBehavior, edits.flag("userScrollEnabled", userScrollEnabled), overscrollEffect,
            ) {
                content()
                trailing?.let { item(Appended) { it() } }
            }
        }
    }
}

@Composable
@NonRestartableComposable
fun LazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal = if (!reverseLayout) Arrangement.Start else Arrangement.End,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    content: LazyListScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationLazyRow(modifier, state, contentPadding, reverseLayout, horizontalArrangement, verticalAlignment, flingBehavior, userScrollEnabled, overscrollEffect, content)
    else {
        val spread = StudioValues.spread(horizontalArrangement)
        studio.Element(listCall("LazyRow", modifier, spread.name, spread.spacing, StudioValues.cross(verticalAlignment), contentPadding, reverseLayout, userScrollEnabled, SurfaceFlow.Horizontal)) { edits ->
            val chosen = edits.spread(spread)
            val trailing = edits.appended("content")
            FoundationLazyRow(
                edits.modifier, state, edits.padding(contentPadding), edits.flag("reverseLayout", reverseLayout),
                if (edits.spreadEdited()) StudioValues.horizontal(chosen.name, chosen.spacing) else horizontalArrangement,
                edits.value("alignment")?.let(StudioValues::verticalOf) ?: verticalAlignment,
                flingBehavior, edits.flag("userScrollEnabled", userScrollEnabled), overscrollEffect,
            ) {
                content()
                trailing?.let { item(Appended) { it() } }
            }
        }
    }
}

@Composable
@NonRestartableComposable
fun LazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
    content: LazyGridScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationLazyVerticalGrid(columns, modifier, state, contentPadding, reverseLayout, verticalArrangement, horizontalArrangement, flingBehavior, userScrollEnabled, overscrollEffect, content)
    else {
        val rows = StudioValues.spread(verticalArrangement)
        val across = StudioValues.spread(horizontalArrangement)
        studio.Element(SurfaceCall("LazyVerticalGrid", modifier, listOf(
            SurfaceField.text("columns", cellsText(columns)),
            SurfaceField.size("spacing", across.spacing),
            SurfaceField.size("lineSpacing", rows.spacing),
        ) + paddingFields(contentPadding) + listOf(
            SurfaceField.flag("reverseLayout", reverseLayout),
            SurfaceField.flag("userScrollEnabled", userScrollEnabled),
        ), slots = listOf("content"), flow = SurfaceFlow.Wrap)) { edits ->
            val trailing = edits.appended("content")
            FoundationLazyVerticalGrid(
                edits.value("columns")?.let(::cellsOf) ?: columns,
                edits.modifier, state, edits.padding(contentPadding), edits.flag("reverseLayout", reverseLayout),
                if (edits.value("lineSpacing") != null) StudioValues.vertical(rows.name, edits.size("lineSpacing", rows.spacing)) else verticalArrangement,
                if (edits.value("spacing") != null) StudioValues.horizontal(across.name, edits.size("spacing", across.spacing)) else horizontalArrangement,
                flingBehavior, edits.flag("userScrollEnabled", userScrollEnabled), overscrollEffect,
            ) {
                content()
                trailing?.let { item(Appended) { it() } }
            }
        }
    }
}

private fun listCall(
    kind: String, modifier: Modifier, spread: String, spacing: Dp, cross: String, padding: PaddingValues,
    reverseLayout: Boolean, userScrollEnabled: Boolean, flow: SurfaceFlow,
) = SurfaceCall(kind, modifier, listOf(
    SurfaceField.choice("arrangement", spread, StudioValues.Spreads),
    SurfaceField.size("spacing", spacing),
    SurfaceField.choice("alignment", cross, StudioValues.Cross),
) + paddingFields(padding) + listOf(
    SurfaceField.flag("reverseLayout", reverseLayout),
    SurfaceField.flag("userScrollEnabled", userScrollEnabled),
), slots = listOf("content"), flow = flow)

private fun paddingFields(padding: PaddingValues) = listOf(
    SurfaceField.size("paddingStart", padding.calculateStartPadding(LayoutDirection.Ltr)),
    SurfaceField.size("paddingTop", padding.calculateTopPadding()),
    SurfaceField.size("paddingEnd", padding.calculateEndPadding(LayoutDirection.Ltr)),
    SurfaceField.size("paddingBottom", padding.calculateBottomPadding()),
)

private fun SurfaceEdits.padding(original: PaddingValues): PaddingValues =
    if (listOf("paddingStart", "paddingTop", "paddingEnd", "paddingBottom").none { value(it) != null }) original
    else PaddingValues(
        size("paddingStart", original.calculateStartPadding(LayoutDirection.Ltr)),
        size("paddingTop", original.calculateTopPadding()),
        size("paddingEnd", original.calculateEndPadding(LayoutDirection.Ltr)),
        size("paddingBottom", original.calculateBottomPadding()),
    )

private val Plain = Density(1f)

private fun cellsText(cells: GridCells): String {
    val small = with(cells) { Plain.calculateCrossAxisCellSizes(1_000, 0) }
    val large = with(cells) { Plain.calculateCrossAxisCellSizes(100_000, 0) }
    return when {
        small.size == large.size -> "Fixed(${small.size})"
        small.firstOrNull() == large.firstOrNull() -> "FixedSize(${small.firstOrNull() ?: 0}dp)"
        else -> "Adaptive(${(100_000f / large.size.coerceAtLeast(1)).roundToInt()}dp)"
    }
}

private val CellsPattern = Regex("""(Fixed|Adaptive|FixedSize)\(\s*([0-9.]+)\s*(dp)?\s*\)""")

private fun cellsOf(text: String): GridCells? {
    val match = CellsPattern.matchEntire(text.trim()) ?: return null
    val amount = match.groupValues[2].toFloatOrNull() ?: return null
    return when (match.groupValues[1]) {
        "Fixed" -> GridCells.Fixed(amount.toInt().coerceAtLeast(1))
        "FixedSize" -> GridCells.FixedSize(amount.coerceAtLeast(1f).dp)
        else -> GridCells.Adaptive(amount.coerceAtLeast(1f).dp)
    }
}
