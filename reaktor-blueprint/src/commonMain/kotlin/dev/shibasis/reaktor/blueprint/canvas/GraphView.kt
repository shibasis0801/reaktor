package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.compose.interaction.FlowViewportGestureConfig
import dev.shibasis.composeflow.compose.interaction.FlowViewportPlatformGestureEffect
import dev.shibasis.composeflow.compose.interaction.LocalFlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.flowPointerViewportGestures
import dev.shibasis.composeflow.compose.interaction.flowWheelAndTrackpadViewportGestures
import dev.shibasis.composeflow.compose.interaction.rememberFlowViewportInteractionState
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine
import dev.shibasis.reaktor.blueprint.product.surfaceGraphIdle
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.compose.Island
import dev.shibasis.reaktor.surface.compose.TooltipGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@Composable
internal fun GraphView(
    painter: SurfaceGraphEngine?,
    state: GraphCanvasState,
    hit: (Float, Float, Float) -> Boolean,
    tap: (Float, Float, Float) -> Boolean,
    modifier: Modifier = Modifier,
    onPane: () -> Unit = {},
    onSize: (IntSize) -> Unit = state::resize,
    selection: () -> Bounds? = { null },
    step: (Int) -> Unit = {},
    open: (Float, Float) -> Boolean = { _, _ -> false },
    enterOpens: Boolean = false,
    focusable: Boolean = true,
    editing: CanvasEditing? = null,
    markers: @Composable (focus: () -> Unit) -> Unit = {},
    draw: DrawScope.(Viewport, Size, Float) -> Unit,
) {
    val density = LocalDensity.current.density
    val idle = surfaceGraphIdle()
    DisposableEffect(painter) { onDispose { painter?.release() } }
    LaunchedEffect(painter) { painter?.run() }
    LaunchedEffect(state) { state.glides() }
    SideEffect { state.density(density.toDouble()) }
    val flow = state.flow
    val interaction = rememberFlowViewportInteractionState()
    val bridge = LocalFlowViewportPlatformBridge.current
    val config = remember(density, state) { FlowViewportGestureConfig(minZoom = state.zoomRange.start * density, maxZoom = state.zoomRange.endInclusive * density) }
    FlowViewportPlatformGestureEffect(flow, interaction, config, bridge)
    val hits by rememberUpdatedState(hit)
    val taps by rememberUpdatedState(tap)
    val panes by rememberUpdatedState(onPane)
    val sizes by rememberUpdatedState(onSize)
    val selected by rememberUpdatedState(selection)
    val steps by rememberUpdatedState(step)
    val opens by rememberUpdatedState(open)
    val edit by rememberUpdatedState(editing)
    val world = remember(flow) { { point: Offset -> flow.screenToFlowPosition(point.x.toDouble(), point.y.toDouble()).let { it.x.toFloat() to it.y.toFloat() } } }
    val reach = { (Reach / state.zoom).toFloat() }
    val last = remember { arrayOfNulls<Pair<TimeSource.Monotonic.ValueTimeMark, Offset>>(1) }
    val paneTap = remember(world) { { point: Offset ->
        val (x, y) = world(point)
        val previous = last[0]
        val twice = previous != null && previous.first.elapsedNow() < DoubleTap && (previous.second - point).getDistance() < DoubleTapSlop * density
        last[0] = if (twice) null else TimeSource.Monotonic.markNow() to point
        taps(x, y, reach()).also { if (twice) opens(x, y) }
    } }
    val paneClick = remember { { panes() } }
    var pointing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val origin = remember { floatArrayOf(0f, 0f) }
    val viewing = if (enterOpens) OpenGraphCommands else GraphCommands
    val own = editing?.commands
    val commands = remember(viewing, editing, own) { if (editing != null && own != null) editing.keyed(CommandSet(viewing.commands + own.commands)) else viewing }
    DisposableEffect(editing, state) {
        val project = world
        editing?.attach(object : CanvasCamera {
            override val seen get() = state.seen()
            override val zoom get() = state.zoom
            override fun look(x: Double, y: Double) = state.look(x, y, state.zoom)
            override fun reveal(box: Bounds) = state.reveal(box.x, box.y, box.width, box.height)
            override fun frame(box: Bounds) = state.fit(box.x, box.y, box.width, box.height, padding = 0.0, maxZoom = state.zoomRange.endInclusive, minZoom = state.zoomRange.start)
            override fun world(window: Offset): Offset? {
                val local = window - Offset(origin[0], origin[1])
                if (local.x < 0 || local.y < 0 || local.x > flow.canvasSize.width || local.y > flow.canvasSize.height) return null
                return project(local).let { (x, y) -> Offset(x, y) }
            }
        })
        onDispose { editing?.attach(null) }
    }
    fun look(boxes: List<Bounds>) = boxes.singleOrNull()?.let { state.centre(it.x, it.y, it.width, it.height) } ?: state.fit(boxes, padding = .08)
    LaunchedEffect(state, editing) { editing?.let { edit -> snapshotFlow { edit.aim }.drop(1).collect { aim -> aim?.boxes?.takeIf { it.isNotEmpty() }?.let(::look) } } }
    TooltipGroup { Island(commands, { command ->
        when (command.value) {
            "fit" -> state.overview()
            "centre" -> editing?.framed()?.takeIf { it.isNotEmpty() }?.let(::look) ?: selected()?.let { state.centre(it.x, it.y, it.width, it.height) }
            "open" -> selected()?.let { opens((it.x + it.width / 2).toFloat(), (it.y + it.height / 2).toFloat()) }
            "zoom-in" -> state.zoomBy(1.25)
            "zoom-out" -> state.zoomBy(1 / 1.25)
            "reset-zoom" -> state.zoomTo(1.0)
            "next-card" -> steps(1)
            "previous-card" -> steps(-1)
            "pan-left" -> if (editing?.travel(-1, 0) != true) state.pan(Pan, 0.0)
            "pan-right" -> if (editing?.travel(1, 0) != true) state.pan(-Pan, 0.0)
            "pan-up" -> if (editing?.travel(0, -1) != true) state.pan(0.0, Pan)
            "pan-down" -> if (editing?.travel(0, 1) != true) state.pan(0.0, -Pan)
            else -> editing?.invoke(command)
        }
    }, modifier.focusRequester(focus), focusable = focusable) {
        Box(
            Modifier.fillMaxSize().clipToBounds()
                .onSizeChanged { sizes(it) }
                .onGloballyPositioned { coordinates ->
                    val at = coordinates.positionInWindow()
                    origin[0] = at.x; origin[1] = at.y
                    interaction.updateCanvasGeometry(at, coordinates.boundsInWindow(), density)
                }
                .pointerHoverIcon(if (pointing) PointerIcon.Hand else PointerIcon.Default)
                .pointerInput(world) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            if (change.type != PointerType.Mouse || event.type == PointerEventType.Scroll) continue
                            val (x, y) = world(change.position)
                            edit?.point(x, y, Offset(origin[0], origin[1]) + change.position)
                            pointing = event.type != PointerEventType.Exit && !event.buttons.areAnyPressed && (hits(x, y, reach()) || edit?.cursor(x, y, reach()) == true)
                            painter?.hover(if (pointing) x else Float.NaN, y, reach())
                        }
                    }
                }
                .flowWheelAndTrackpadViewportGestures(flow, interaction, config, bridge)
                .flowPointerViewportGestures(flow, interaction, config, onPaneClick = paneClick, onPaneTap = paneTap)
                .then(if (editing == null) Modifier else Modifier.pointerInput(editing, world) { edits(editing, world, origin, reach) })
                .drawBehind {
                    painter?.version?.intValue
                    painter?.inset(state.insetStart.toFloat(), state.insetTop.toFloat(), state.insetEnd.toFloat(), state.insetBottom.toFloat())
                    painter?.aim(state.aim, state.landing, idle)
                    withTransform({ scale(density, density, Offset.Zero) }) {
                        val view = state.viewport()
                        draw(view, Size(size.width / density, size.height / density), density)
                        editing?.let { edit -> withTransform({ translate(view.x.toFloat(), view.y.toFloat()); scale(view.zoom.toFloat(), view.zoom.toFloat(), Offset.Zero) }) { edit.overlay(this, view.zoom) } }
                    }
                },
        ) { markers { if (focusable) focus.requestFocus() } }
    } }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.edits(editing: CanvasEditing, world: (Offset) -> Pair<Float, Float>, origin: FloatArray, reach: () -> Float) {
    var last: Pair<TimeSource.Monotonic.ValueTimeMark, Offset>? = null
    var clicks = 0
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Press) continue
            val change = event.changes.firstOrNull() ?: continue
            val touch = change.type == PointerType.Touch
            val (x, y) = world(change.position)
            val window = Offset(origin[0], origin[1]) + change.position
            if (event.buttons.isSecondaryPressed) {
                var moved = false
                while (true) {
                    val next = awaitPointerEvent().changes.firstOrNull { it.id == change.id } ?: break
                    if ((next.position - change.position).getDistance() > MenuSlop * density) moved = true
                    if (!next.pressed) break
                }
                if (!moved) editing.menu(x, y, window)
                continue
            }
            if (!touch && !event.buttons.isPrimaryPressed) continue
            val previous = last
            clicks = if (previous != null && previous.first.elapsedNow() < DoubleTap && (previous.second - change.position).getDistance() < DoubleTapSlop * density) clicks + 1 else 1
            last = TimeSource.Monotonic.markNow() to change.position
            if (!editing.press(x, y, window, reach(), event.keyboardModifiers, clicks, touch)) continue
            change.consume()
            while (true) {
                val next = awaitPointerEvent().changes.firstOrNull { it.id == change.id } ?: break
                val (nx, ny) = world(next.position)
                val at = Offset(origin[0], origin[1]) + next.position
                if (!next.pressed) { editing.release(nx, ny, at); next.consume(); break }
                if (next.positionChange() != Offset.Zero) { editing.drag(nx, ny, at); next.consume() }
            }
        }
    }
}

internal val GraphCommands = CommandSet(listOf(
    Command(CommandId("fit"), "Frame everything", Chord.Of(KeyName.Digit1, shift = true)),
    Command(CommandId("centre"), "Frame the selection", Chord.Of(KeyName.F)),
    Command(CommandId("zoom-in"), "Zoom in", Chord.Of(KeyName.Equals)),
    Command(CommandId("zoom-out"), "Zoom out", Chord.Of(KeyName.Minus)),
    Command(CommandId("reset-zoom"), "Reset zoom", Chord.Of(KeyName.Digit0, shift = true)),
    Command(CommandId("next-card"), "Next card", Chord.Of(KeyName.RightBracket)),
    Command(CommandId("previous-card"), "Previous card", Chord.Of(KeyName.LeftBracket)),
    Command(CommandId("pan-left"), "Pan left", Chord.Of(KeyName.Left)),
    Command(CommandId("pan-right"), "Pan right", Chord.Of(KeyName.Right)),
    Command(CommandId("pan-up"), "Pan up", Chord.Of(KeyName.Up)),
    Command(CommandId("pan-down"), "Pan down", Chord.Of(KeyName.Down)),
))

private val OpenGraphCommands = CommandSet(GraphCommands.commands + Command(CommandId("open"), "Open the selection", Chord.Of(KeyName.Enter)))

private class Shown(val indices: List<Int>, val view: Viewport, val zoom: Double)

@Composable
internal fun GraphMarkers(state: GraphCanvasState, boxes: List<Bounds>, id: (Int) -> Any, minZoom: Double = 0.0, content: @Composable (Int, Double) -> Unit) {
    val flow = state.flow
    val density = LocalDensity.current.density
    var shown by remember(boxes, flow, density) { mutableStateOf<Shown?>(null) }
    LaunchedEffect(boxes, flow, density) {
        var seen: Pair<Viewport, IntSize>? = null
        while (true) {
            var now = snapshotFlow { flow.viewport to flow.canvasSize }.first { it != seen }
            if (seen != null) while (true) { delay(SettleMillis); val next = flow.viewport to flow.canvasSize; if (next == now) break; now = next }
            seen = now
            val (view, canvas) = now
            val scale = view.zoom
            val mx = canvas.width / scale / 4; val my = canvas.height / scale / 4
            val left = -view.x / scale - mx; val top = -view.y / scale - my
            val right = (canvas.width - view.x) / scale + mx; val bottom = (canvas.height - view.y) / scale + my
            shown = Shown(if (scale / density < minZoom) emptyList() else boxes.indices.filter { boxes[it].let { box -> box.x < right && box.y < bottom && box.x + box.width > left && box.y + box.height > top } }, view, scale / density)
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val view = shown?.view ?: return@graphicsLayer
        translationX = view.x.toFloat(); translationY = view.y.toFloat()
        scaleX = (view.zoom / density).toFloat(); scaleY = scaleX
        transformOrigin = TransformOrigin(0f, 0f)
    }) {
        val now = shown ?: return@Box
        now.indices.forEach { i -> key(id(i)) { content(i, now.zoom) } }
    }
}

internal fun Modifier.at(x: Double, y: Double, width: Double, height: Double) = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed((width * density).roundToInt(), (height * density).roundToInt()))
    layout(0, 0) { placeable.place((x * density).roundToInt(), (y * density).roundToInt()) }
}

@Composable
internal fun Said(text: String) {
    if (text.isNotEmpty()) Box(Modifier.fillMaxSize().semantics { this.text = AnnotatedString(text) })
}

private const val SettleMillis = 100L
private const val Reach = 10.0
private const val Pan = 120.0
private const val DoubleTapSlop = 8f
private const val MenuSlop = 4f
private val DoubleTap = 350.milliseconds
