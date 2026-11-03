package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.compose.interaction.FlowViewportGestureConfig
import dev.shibasis.composeflow.compose.interaction.LocalFlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.flowViewportGestures
import dev.shibasis.composeflow.compose.interaction.rememberFlowViewportInteractionState
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.reaktor.blueprint.Bounds
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphEngine
import dev.shibasis.reaktor.blueprint.product.surfaceGraphIdle
import dev.shibasis.reaktor.blueprint.editor.GraphEditor
import dev.shibasis.reaktor.blueprint.editor.viewerCommands
import dev.shibasis.reaktor.surface.Availability
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
    projectedHit: ((Float, Float, Float) -> Boolean)? = null,
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
    val config = remember(density, state, state.minimumZoom) { FlowViewportGestureConfig(minZoom = state.minimumZoom * density, maxZoom = state.zoomRange.endInclusive * density) }
    val hits by rememberUpdatedState(hit)
    val taps by rememberUpdatedState(tap)
    val panes by rememberUpdatedState(onPane)
    val sizes by rememberUpdatedState(onSize)
    val selected by rememberUpdatedState(selection)
    val steps by rememberUpdatedState(step)
    val edit by rememberUpdatedState(editing)
    val projection by rememberUpdatedState(projectedHit)
    val world = remember(flow) { { point: Offset -> flow.screenToFlowPosition(point.x.toDouble(), point.y.toDouble()).let { it.x.toFloat() to it.y.toFloat() } } }
    val reach = { (Reach / state.zoom).toFloat() }
    val pointer = remember { arrayOfNulls<Offset>(1) }
    val last = remember { arrayOfNulls<Triple<TimeSource.Monotonic.ValueTimeMark, Offset, Viewport>>(1) }
    val paneTap = remember(world) { { point: Offset ->
        val (x, y) = world(point)
        val previous = last[0]
        val twice = previous != null && previous.third == flow.viewport && previous.first.elapsedNow() < DoubleTap && (previous.second - point).getDistance() < DoubleTapSlop * density
        last[0] = if (twice) null else Triple(TimeSource.Monotonic.markNow(), point, flow.viewport)
        if (twice) { state.zoomAt(2.0, point.x / density.toDouble(), point.y / density.toDouble()); true } else taps(x, y, reach())
    } }
    val paneClick = remember { { panes() } }
    var pointing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val origin = remember { floatArrayOf(0f, 0f) }
    val commands = editing?.commands ?: viewerCommands(enterOpens)
    DisposableEffect(editing, state) {
        (editing as? GraphEditor)?.navigation(state::viewport, state::restore)
        val project = world
        editing?.attach(object : CanvasCamera {
            override val seen get() = state.seen()
            override val zoom get() = state.zoom
            override fun look(x: Double, y: Double) = state.look(x, y, state.zoom)
            override fun reveal(box: Bounds) = state.reveal(box.x, box.y, box.width, box.height)
            override fun frame(box: Bounds) = state.fit(box.x, box.y, box.width, box.height, padding = 0.0, maxZoom = state.zoomRange.endInclusive, minZoom = state.minimumZoom)
            override fun world(window: Offset): Offset? {
                val local = window - Offset(origin[0], origin[1])
                if (local.x < 0 || local.y < 0 || local.x > flow.canvasSize.width || local.y > flow.canvasSize.height) return null
                return project(local).let { (x, y) -> Offset(x, y) }
            }
        })
        onDispose { editing?.attach(null); (editing as? GraphEditor)?.navigation(null, null) }
    }
    fun look(boxes: List<Bounds>) = boxes.singleOrNull()?.let { state.centre(it.x, it.y, it.width, it.height) } ?: state.fit(boxes, padding = .08)
    LaunchedEffect(state, editing) { editing?.let { edit -> snapshotFlow { edit.aim }.drop(1).collect { aim -> aim?.boxes?.takeIf { it.isNotEmpty() }?.let(::look) } } }
    val space = remember { BooleanArray(2) }
    val window = LocalWindowInfo.current
    LaunchedEffect(window.isWindowFocused) { if (!window.isWindowFocused) { space[0] = false; space[1] = false } }
    fun zoom(factor: Double) {
        val at = pointer[0]?.takeIf { flow.canvasSize.width > 0 && flow.canvasSize.height > 0 &&
            it.x >= 0 && it.y >= 0 && it.x <= flow.canvasSize.width && it.y <= flow.canvasSize.height }
        at?.let { state.zoomAt(factor, it.x / density.toDouble(), it.y / density.toDouble()) } ?: state.zoomBy(factor)
    }
    val run: (CommandId) -> Unit = run@{ command ->
        val current = editing?.commands ?: viewerCommands(enterOpens)
        if (current.commands.firstOrNull { it.id == command }?.availability != Availability.Available) return@run
        when (command.value) {
            "fit" -> state.overview()
            "centre-selection" -> editing?.framed()?.takeIf { it.isNotEmpty() }?.let(::look) ?: selected()?.let { state.centre(it.x, it.y, it.width, it.height) }
            "edit-rename-enter" -> editing?.invoke(command)
            "fit-selection" -> editing?.framed()?.takeIf { it.isNotEmpty() }?.let { state.fit(it, padding = .08) } ?: selected()?.let { state.fit(it.x, it.y, it.width, it.height) }
            "zoom-in", "zoom-in-primary", "zoom-in-primary-shift" -> zoom(1.25)
            "zoom-out", "zoom-out-primary" -> zoom(1 / 1.25)
            "reset-zoom", "reset-zoom-primary" -> state.zoomTo(1.0)
            "next-card" -> steps(1)
            "previous-card" -> steps(-1)
            "pan-left" -> if (editing?.travel(-1, 0) != true) state.pan(Pan, 0.0)
            "pan-right" -> if (editing?.travel(1, 0) != true) state.pan(-Pan, 0.0)
            "pan-up" -> if (editing?.travel(0, -1) != true) state.pan(0.0, Pan)
            "pan-down" -> if (editing?.travel(0, 1) != true) state.pan(0.0, -Pan)
            "page-up" -> state.page(0, -1)
            "page-down" -> state.page(0, 1)
            "page-left" -> state.page(-1, 0)
            "page-right" -> state.page(1, 0)
            else -> editing?.invoke(command)
        }
    }
    val currentRun by rememberUpdatedState(run)
    DisposableEffect(editing, focus) {
        val release = (editing as? GraphEditor)?.commandsFromCanvas({ currentRun(it) }, { if (focusable) focus.requestFocus() })
        onDispose { release?.invoke() }
    }
    val held = if (editing == null) Modifier else Modifier.onPreviewKeyEvent { event ->
        if (event.key != Key.Spacebar) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown -> if (!space[0]) { space[0] = true; space[1] = false }
            KeyEventType.KeyUp -> { val held = space[0]; space[0] = false; if (held && !space[1]) commands.commands.firstOrNull { it.chord == Chord.Of(KeyName.Space) }?.let { run(it.id) } }
        }
        true
    }
    TooltipGroup { Island(commands, run, modifier.then(held).onFocusChanged { if (!it.hasFocus) { space[0] = false; space[1] = false } }.focusRequester(focus), focusable = focusable) {
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
                            pointer[0] = change.position
                            val (x, y) = world(change.position)
                            edit?.point(x, y, Offset(origin[0], origin[1]) + change.position)
                            pointing = event.type != PointerEventType.Exit && !event.buttons.areAnyPressed && (hits(x, y, reach()) || edit?.cursor(x, y, reach()) == true)
                            painter?.hover(if (pointing) x else Float.NaN, y, reach())
                        }
                    }
                }
                .flowViewportGestures(flow, interaction, config, bridge, onPaneClick = paneClick, onPaneTap = paneTap)
                .then(if (editing == null) Modifier else Modifier.pointerInput(editing, world) { edits(editing, world, origin, reach, space, { x, y, distance -> projection?.invoke(x, y, distance) == true }) { at -> state.zoomAt(2.0, at.x / density.toDouble(), at.y / density.toDouble()) } })
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

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.edits(editing: CanvasEditing, world: (Offset) -> Pair<Float, Float>, origin: FloatArray, reach: () -> Float, space: BooleanArray, projectedHit: ((Float, Float, Float) -> Boolean)?, zoom: (Offset) -> Unit) {
    var last: Pair<TimeSource.Monotonic.ValueTimeMark, Offset>? = null
    var clicks = 0
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Press) continue
            val change = event.changes.firstOrNull() ?: continue
            if (change.isConsumed) continue
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
                if (!moved && projectedHit?.invoke(x, y, reach()) != true) editing.menu(x, y, window)
                continue
            }
            if (!touch && !event.buttons.isPrimaryPressed) continue
            if (space[0]) { space[1] = true; continue }
            val previous = last
            clicks = if (previous != null && previous.first.elapsedNow() < DoubleTap && (previous.second - change.position).getDistance() < DoubleTapSlop * density) clicks + 1 else 1
            last = TimeSource.Monotonic.markNow() to change.position
            if (clicks == 2) {
                change.consume()
                var moved = false
                while (true) {
                    val next = awaitPointerEvent().changes.firstOrNull { it.id == change.id } ?: break
                    if ((next.position - change.position).getDistance() > DoubleTapSlop * density) moved = true
                    next.consume()
                    if (!next.pressed) { if (!moved) zoom(change.position); break }
                }
                last = null
                continue
            }
            val selectedBefore = (editing as? GraphEditor)?.selection
            val projected = projectedHit?.invoke(x, y, reach()) == true
            if (!projected && !editing.press(x, y, window, reach(), event.keyboardModifiers, clicks, touch)) continue
            if (!touch) change.consume()
            while (true) {
                var nextEvent = awaitPointerEvent()
                if (touch && nextEvent.changes.count { it.pressed } > 1) {
                    if (editing is GraphEditor && selectedBefore != null) editing.cancelPointer(selectedBefore)
                    else if (!projected) editing.invoke(CommandId("edit-escape"))
                    last = null
                    while (nextEvent.changes.any { it.pressed }) nextEvent = awaitPointerEvent()
                    break
                }
                val next = nextEvent.changes.firstOrNull { it.id == change.id } ?: break
                val (nx, ny) = world(next.position)
                val at = Offset(origin[0], origin[1]) + next.position
                if (!next.pressed) { if (!projected) editing.release(nx, ny, at); next.consume(); break }
                if (next.positionChange() != Offset.Zero) { if (!projected) editing.drag(nx, ny, at); next.consume() }
            }
        }
    }
}

private class Shown(val indices: List<Int>, val view: Viewport, val zoom: Double)

@Composable
internal fun GraphMarkers(state: GraphCanvasState, boxes: List<Bounds>, id: (Int) -> Any, minZoom: Double = 0.0, content: @Composable (Int, Double) -> Unit) {
    val flow = state.flow
    val ambient = LocalDensity.current
    val density = ambient.density
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
        val view = flow.viewport
        translationX = view.x.toFloat(); translationY = view.y.toFloat()
        scaleX = (view.zoom / density).toFloat(); scaleY = scaleX
        transformOrigin = TransformOrigin(0f, 0f)
    }) {
        val now = shown ?: return@Box
        now.indices.forEach { i -> key(id(i)) {
            val factor = minOf(1.0, 8190.0 / (maxOf(boxes[i].width, boxes[i].height, 1.0) * density)).toFloat()
            if (factor == 1f) content(i, now.zoom)
            else Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1f / factor; scaleY = scaleX; transformOrigin = TransformOrigin(0f, 0f)
            }) {
                CompositionLocalProvider(LocalDensity provides Density(density * factor, ambient.fontScale)) { content(i, now.zoom) }
            }
        } }
    }
}

internal fun Modifier.at(x: Double, y: Double, width: Double, height: Double) = layout { measurable, _ ->
    val physicalWidth = width * density
    val physicalHeight = height * density
    val placeable = measurable.measure(Constraints.fixed(physicalWidth.roundToInt(), physicalHeight.roundToInt()))
    val physicalX = x * density
    val physicalY = y * density
    val left = physicalX.roundToInt()
    val top = physicalY.roundToInt()
    val widthScale = if (placeable.width == 0) 1f else (physicalWidth / placeable.width).toFloat()
    val heightScale = if (placeable.height == 0) 1f else (physicalHeight / placeable.height).toFloat()
    layout(0, 0) {
        if (physicalX == left.toDouble() && physicalY == top.toDouble() && widthScale == 1f && heightScale == 1f) {
            placeable.place(left, top)
        } else placeable.placeWithLayer(left, top) {
            translationX = (physicalX - left).toFloat()
            translationY = (physicalY - top).toFloat()
            scaleX = widthScale
            scaleY = heightScale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
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
