package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import dev.shibasis.reaktor.surface.FieldType
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a slider's appearance draws: where the value sits along the track, 0 at its start and 1 at its end, and whether it can be moved. */
data class SliderProperties(val fraction: Float, val enabled: Boolean)

/**
 * How a slider looks: a thumb and a track. The slider lays them out (the thumb is measured first,
 * the track takes the rest of the width, and the thumb's centre sits at the value's place along it)
 * and owns touch, keys and accessibility. The track draws from its start edge; in a right-to-left
 * layout the slider mirrors it.
 */
interface SliderAppearance {
    /**
     * The least the slider measures, whatever it is given: at least as wide as the thumb at rest,
     * so a thumb that narrows while held narrows inside its own slot and the track stays put.
     */
    val minimumSize: DpSize get() = DpSize.Zero

    /** The thumb, at the size it picks. [held] while it is being dragged. */
    @Composable
    fun Thumb(properties: SliderProperties, held: Boolean, theme: ThemeSnapshot)

    /** The track across the width it is given, at the height it picks. [thumbWidth] is the thumb's current width in pixels, to leave room around it. */
    @Composable
    fun Track(properties: SliderProperties, thumbWidth: Float, theme: ThemeSnapshot)
}

/**
 * One value from a range, chosen by dragging the thumb or by tapping the track where it should be.
 * [onValueChange] runs as it moves; [onValueChangeFinished] once a drag or tap lets go, so a caller
 * that seeks can seek once.
 *
 * A screen reader adjusts it with the progress action, and a keyboard with the arrows (a hundredth
 * of the range at a time), Page Up and Down (a tenth), Home and End. It is never shorter than a
 * 48dp touch target: a thinner track and thumb sit centred in that height.
 */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    appearance: SliderAppearance = LocalAppearances.current[Appearance.Slider],
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) DrawnSlider(value, onValueChange, modifier, enabled, valueRange, onValueChangeFinished, appearance)
    else studio.Element(SurfaceCall("Slider", modifier, listOf(SurfaceField("value", FieldType.Fraction, value.toString(), editable = false),
        SurfaceField.flag("enabled", enabled)), appearance, Appearance.Slider)) { edits ->
        DrawnSlider(value, edits.handler("onValueChange", onValueChange), edits.modifier, edits.flag("enabled", enabled), valueRange,
            onValueChangeFinished?.let { edits.action("onValueChangeFinished", it) }, edits.appearance(appearance))
    }
}

/** Where a finger is along the slider, and how wide its parts are, in pixels. */
private class SliderSpan {
    var width by mutableFloatStateOf(0f)
    var thumb by mutableFloatStateOf(0f)
    var finger = 0f

    /** The fraction a thumb centred at [x] stands for: its centre runs from half a thumb in at each end. */
    fun fractionAt(x: Float): Float {
        val end = max(width - thumb / 2, 0f)
        val start = min(thumb / 2, end)
        return if (end == start) 0f else ((x.coerceIn(start, end) - start) / (end - start))
    }
}

private enum class SliderPart { Thumb, Track }

@Composable
private fun DrawnSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (() -> Unit)?,
    appearance: SliderAppearance,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val theme = LocalThemeSnapshot.current
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val shown by rememberUpdatedState(value)
    val span = remember { SliderSpan() }
    val source = remember { MutableInteractionSource() }
    val dragging by source.collectIsDraggedAsState()
    val length = valueRange.endInclusive - valueRange.start
    val fraction = if (length == 0f) 0f else (value.coerceIn(valueRange) - valueRange.start) / length
    val properties = SliderProperties(fraction, enabled)

    fun moveTo(x: Float) {
        val next = valueRange.start + span.fractionAt(x) * length
        if (next != shown) change(next)
    }

    val drag = rememberDraggableState { delta ->
        span.finger += delta
        moveTo(span.finger)
    }

    Layout(
        content = {
            Box(Modifier.layoutId(SliderPart.Thumb).wrapContentWidth().onSizeChanged { span.thumb = it.width.toFloat() }) {
                appearance.Thumb(properties, dragging, theme)
            }
            Box(Modifier.layoutId(SliderPart.Track).then(if (rtl) Modifier.graphicsLayer { scaleX = -1f } else Modifier)) {
                appearance.Track(properties, span.thumb, theme)
            }
        },
        modifier = modifier
            .touchTarget(TouchTarget)
            .requiredSizeIn(minWidth = appearance.minimumSize.width, minHeight = appearance.minimumSize.height)
            .semantics {
                if (!enabled) disabled()
                setProgress { target ->
                    val next = target.coerceIn(valueRange)
                    if (next == shown) false else {
                        change(next)
                        finished?.invoke()
                        true
                    }
                }
            }
            .progressSemantics(value, valueRange, 0)
            .focusable(enabled, source)
            .slideOnKeys(enabled, valueRange, { shown }, rtl, { change(it) }, { finished?.invoke() })
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(span, rtl) {
                    detectTapGestures(
                        onPress = { span.finger = if (rtl) span.width - it.x else it.x },
                        onTap = {
                            moveTo(span.finger)
                            finished?.invoke()
                        },
                    )
                },
            )
            .draggable(
                drag,
                Orientation.Horizontal,
                enabled = enabled,
                interactionSource = source,
                startDragImmediately = dragging,
                reverseDirection = rtl,
                onDragStopped = { finished?.invoke() },
            ),
    ) { measurables, constraints ->
        val thumb = measurables.first { it.layoutId == SliderPart.Thumb }.measure(constraints)
        val track = measurables.first { it.layoutId == SliderPart.Track }.measure(
            constraints.offset(horizontal = -thumb.width).copy(minHeight = 0),
        )
        val width = thumb.width + track.width
        val height = max(track.height, thumb.height)
        span.width = width.toFloat()
        layout(width, height) {
            track.placeRelative(thumb.width / 2, (height - track.height) / 2)
            thumb.placeRelative((track.width * fraction).roundToInt(), (height - thumb.height) / 2)
        }
    }
}

/** At least [size] tall and wide, laid out around the slider, which stays centred, rather than stretching it. */
private fun Modifier.touchTarget(size: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val width = max(placeable.width, size.roundToPx())
    val height = max(placeable.height, size.roundToPx())
    layout(width, height) {
        placeable.place(((width - placeable.width) / 2f).roundToInt(), ((height - placeable.height) / 2f).roundToInt())
    }
}

/**
 * The keys a slider answers: each arrow moves it a hundredth of the range (left and right follow the
 * layout direction), Page Up and Down a tenth, Home and End to its ends. Letting the key go finishes
 * the change, as letting a drag go does.
 */
private fun Modifier.slideOnKeys(
    enabled: Boolean,
    range: ClosedFloatingPointRange<Float>,
    value: () -> Float,
    rtl: Boolean,
    change: (Float) -> Unit,
    finish: () -> Unit,
): Modifier = onKeyEvent { event ->
    if (!enabled) return@onKeyEvent false
    val step = abs(range.endInclusive - range.start) / KeySteps
    val sign = if (rtl) -1 else 1
    val target = when (event.key) {
        Key.DirectionUp -> value() + step
        Key.DirectionDown -> value() - step
        Key.DirectionRight -> value() + sign * step
        Key.DirectionLeft -> value() - sign * step
        Key.PageUp -> value() + PageSteps * step
        Key.PageDown -> value() - PageSteps * step
        Key.MoveHome -> range.start
        Key.MoveEnd -> range.endInclusive
        else -> return@onKeyEvent false
    }
    when (event.type) {
        KeyEventType.KeyDown -> change(target.coerceIn(range))
        KeyEventType.KeyUp -> finish()
        else -> return@onKeyEvent false
    }
    true
}

private const val KeySteps = 100
private const val PageSteps = 10
private val TouchTarget = 48.dp

/** A plain slider: a hairline track that fills to the thumb, and a round thumb. */
val BareSlider: SliderAppearance = object : SliderAppearance {
    @Composable
    override fun Thumb(properties: SliderProperties, held: Boolean, theme: ThemeSnapshot) {
        Box(Modifier.size(if (held) 24.dp else 20.dp).clip(CircleShape).background(if (properties.enabled) Color.Black else Color.Gray))
    }

    @Composable
    override fun Track(properties: SliderProperties, thumbWidth: Float, theme: ThemeSnapshot) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.LightGray)) {
            Box(Modifier.fillMaxWidth(properties.fraction).height(4.dp).background(if (properties.enabled) Color.Black else Color.Gray))
        }
    }
}
