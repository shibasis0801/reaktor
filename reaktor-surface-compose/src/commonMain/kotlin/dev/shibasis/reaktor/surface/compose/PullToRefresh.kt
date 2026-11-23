package dev.shibasis.reaktor.surface.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

/**
 * How far a pull-to-refresh indicator has come down: 0 hidden, 1 at the distance that refreshes,
 * more while a pull goes past it. It moves every frame of a pull, so an appearance reads it where it
 * draws or places, not where it composes.
 */
@Stable
class PullProgress internal constructor() {
    internal val shown = Animatable(0f)
    val fraction: Float get() = shown.value
}

/**
 * How pull-to-refresh looks: the indicator drawn over the top centre of the content, and how far a
 * pull must come to refresh.
 */
interface PullToRefreshAppearance {
    /** How far a pull must come, once the drag is halved, to refresh when it lets go. */
    val threshold: Dp get() = 80.dp

    /** The indicator. [refreshing] while a refresh runs; [progress] while pulled or settling. */
    @Composable
    fun Indicator(progress: PullProgress, refreshing: Boolean, theme: ThemeSnapshot)
}

/**
 * Pulling the top of a scrolling list further down, past its start, refreshes it: the platform's
 * own gesture. The pull counts half the finger's travel and stiffens past the threshold; letting go
 * beyond it calls [onRefresh], and the indicator then holds at the threshold while [refreshing] is
 * true and goes when it turns false. Flinging up while it shows takes it back first.
 *
 * [content] should scroll (a lazy list or grid, or a scrolling column): the gesture is the scroll
 * that is left over once the content is at its top.
 */
@Composable
fun PullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    appearance: PullToRefreshAppearance = LocalAppearances.current[Appearance.PullToRefresh],
    content: @Composable BoxScope.() -> Unit,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) PulledBox(refreshing, onRefresh, modifier, enabled, appearance, content)
    else studio.Element(SurfaceCall("PullToRefresh", modifier, listOf(SurfaceField.flag("refreshing", refreshing, editable = false), SurfaceField.flag("enabled", enabled)),
        appearance, Appearance.PullToRefresh, listOf("content"))) { edits ->
        PulledBox(refreshing, edits.action("onRefresh", onRefresh), edits.modifier, edits.flag("enabled", enabled), edits.appearance(appearance), edits.scoped("content", content))
    }
}

@Composable
private fun PulledBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    appearance: PullToRefreshAppearance,
    content: @Composable BoxScope.() -> Unit,
) {
    val progress = remember { PullProgress() }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { appearance.threshold.roundToPx() }
    val refresh by rememberUpdatedState(onRefresh)
    val pull = remember(progress, scope) { Pull(progress, scope) }
    pull.threshold = threshold
    pull.enabled = enabled
    pull.refreshing = refreshing
    pull.onRefresh = { refresh() }
    // The first answer is where it starts, not a change to animate.
    LaunchedEffect(refreshing) {
        pull.settle(refreshing, animate = pull.settled)
        pull.settled = true
    }
    Box(modifier.nestedScroll(pull)) {
        content()
        Box(Modifier.align(Alignment.TopCenter)) { appearance.Indicator(progress, refreshing, LocalThemeSnapshot.current) }
    }
}

/**
 * The gesture, as Material 3's pull-to-refresh measures it: half the finger's travel, stiffening
 * past the threshold (at most twice its distance again), released over the threshold to refresh.
 */
private class Pull(private val progress: PullProgress, private val scope: CoroutineScope) : NestedScrollConnection {
    var threshold = 0
    var enabled = true
    var refreshing = false
    var onRefresh: () -> Unit = {}
    var settled = false
    private var pulled by mutableFloatStateOf(0f)
    private var offset by mutableFloatStateOf(0f)
    private val adjusted: Float get() = pulled * DragMultiplier

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = when {
        progress.shown.isRunning || !enabled -> Offset.Zero
        source == NestedScrollSource.UserInput && available.y < 0 -> consume(available)
        else -> Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = when {
        progress.shown.isRunning || !enabled -> Offset.Zero
        source == NestedScrollSource.UserInput -> consume(available).also {
            scope.launch { if (!progress.shown.isRunning) progress.shown.snapTo(offset / threshold) }
        }
        else -> Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity = Velocity(0f, release(available.y))

    private fun consume(available: Offset): Offset {
        if (refreshing) return Offset.Zero
        val next = (pulled + available.y).coerceAtLeast(0f)
        val taken = next - pulled
        pulled = next
        offset = stretched()
        return Offset(0f, taken)
    }

    private suspend fun release(velocity: Float): Float {
        if (refreshing) return 0f
        if (adjusted > threshold) onRefresh()
        val taken = if (pulled == 0f || velocity < 0f) 0f else velocity
        hide()
        pulled = 0f
        return taken
    }

    private fun stretched(): Float {
        if (adjusted <= threshold) return adjusted
        val overshoot = abs(adjusted / threshold) - 1f
        val tension = overshoot.coerceIn(0f, 2f)
        return threshold + threshold * (tension - tension.pow(2) / 4)
    }

    suspend fun settle(refreshing: Boolean, animate: Boolean) {
        if (!animate) {
            progress.shown.snapTo(if (refreshing) 1f else 0f)
            offset = if (refreshing) threshold.toFloat() else 0f
        } else if (refreshing) {
            try {
                progress.shown.animateTo(1f)
            } finally {
                pulled = threshold.toFloat()
                offset = threshold.toFloat()
            }
        } else hide()
    }

    private suspend fun hide() {
        try {
            progress.shown.animateTo(0f)
        } finally {
            pulled = 0f
            offset = 0f
        }
    }

    private companion object {
        const val DragMultiplier = 0.5f
    }
}

/** A plain indicator: a grey dot that comes down with the pull, darker once it will refresh or while it does. */
val BarePullToRefresh: PullToRefreshAppearance = object : PullToRefreshAppearance {
    @Composable
    override fun Indicator(progress: PullProgress, refreshing: Boolean, theme: ThemeSnapshot) {
        Box(
            Modifier
                .size(32.dp)
                .graphicsLayer {
                    translationY = progress.fraction * threshold.toPx() - size.height
                    alpha = if (progress.fraction > 0f || refreshing) 1f else 0f
                }
                .background(if (refreshing || progress.fraction >= 1f) Color.DarkGray else Color.LightGray, CircleShape),
        )
    }
}
