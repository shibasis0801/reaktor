package dev.shibasis.reaktor.blueprint.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.shibasis.reaktor.blueprint.BlueprintAgent
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.colors
import dev.shibasis.reaktor.ui.machinesignal.surface.SignalChromeButton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun BlueprintAgentBadge(
    agent: BlueprintAgent,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    visible: Boolean = true,
    tag: String = "blueprint-agent",
    fillHeight: Boolean = false,
) {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(agent.activity, visible) {
        phase.floatValue = 0f
        agentBadgeFrames(agent.activity, visible) { phase.floatValue = it }
    }
    val colors = MachineSignal.colors
    val tone = agentBadgeHeat(agent.effort, colors.canvas.luminance() > .5f, colors.textMuted)
    val description = listOfNotNull(agentBadgeState(agent).takeIf(String::isNotBlank),
        agentBadgeTuple(agent).takeIf(String::isNotBlank), agent.effort?.takeIf(String::isNotBlank)?.let { "reasoning $it" },
        "Open agent gallery").joinToString(", ")
    Row(modifier.testTag(tag), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MachineSignal.AgentBadge.gap)) {
        Button(onOpen, Modifier.size(MachineSignal.AgentBadge.size).testTag("$tag-icon")
            .semantics { contentDescription = description }, appearance = SignalChromeButton) {
            Canvas(Modifier.size(MachineSignal.AgentBadge.size)) {
                drawBlueprintAgentIcon(agent, tone, phase.floatValue)
            }
        }
        if (!compact) Column(Modifier.weight(1f).then(if (fillHeight) Modifier.fillMaxHeight() else Modifier).testTag("$tag-tuple")
            .semantics { contentDescription = description }.clickable(onClickLabel = "Open agent gallery", onClick = onOpen),
            verticalArrangement = Arrangement.spacedBy(MachineSignal.AgentBadge.gap)) {
            agentBadgeState(agent).takeIf(String::isNotBlank)?.let { state ->
                Text(state, Modifier.fillMaxWidth().testTag("$tag-state"), role = Type.Meta, lines = Int.MAX_VALUE, ink = dev.shibasis.reaktor.surface.Ink.Muted)
            }
            agentBadgeTuple(agent).takeIf(String::isNotBlank)?.let { tuple -> Text(tuple, role = Type.Meta, lines = Int.MAX_VALUE) }
        }
    }
}

internal fun agentBadgeState(agent: BlueprintAgent): String = listOfNotNull(
    agent.label.takeIf(String::isNotBlank),
    agent.activity.takeUnless { it == BlueprintAgent.Activity.Unavailable }?.label,
).distinct().joinToString(" · ") + if (agent.otherAgents > 0) " · +${agent.otherAgents}" else ""

internal fun agentBadgeTuple(agent: BlueprintAgent): String =
    listOfNotNull(agent.model?.takeIf(String::isNotBlank), agent.role?.takeIf(String::isNotBlank))
        .joinToString(", ").let { if (it.isBlank()) "" else "($it)" }

internal fun agentBadgeHeat(effort: String?, light: Boolean, unknown: Color): Color {
    val level = when (effort?.trim()?.lowercase()) {
        "none" -> 0
        "minimal" -> 1
        "low" -> 2
        "medium" -> 3
        "high" -> 4
        "xhigh", "extra_high", "extra-high", "extra high" -> 5
        "max" -> 6
        "ultra" -> 7
        else -> return unknown
    }
    return (if (light) MachineSignal.AgentBadge.lightHeat else MachineSignal.AgentBadge.darkHeat)[level]
}

internal suspend fun agentBadgeFrames(activity: BlueprintAgent.Activity, visible: Boolean, paint: (Float) -> Unit) {
    if (!visible || activity != BlueprintAgent.Activity.Live) return
    val start = withFrameNanos { it }
    val period = MachineSignal.AgentBadge.cycleMillis * 1_000_000L
    while (currentCoroutineContext().isActive) withFrameNanos { time ->
        paint(((time - start) % period).toFloat() / period)
    }
}

internal fun DrawScope.drawBlueprintAgentIcon(
    agent: BlueprintAgent,
    tone: Color,
    phase: Float,
    bounds: Rect = Rect(Offset.Zero, size),
) {
    val stroke = MachineSignal.AgentBadge.stroke.toPx()
    val inset = stroke / 2
    val ring = Rect(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
    val radius = min(MachineSignal.AgentBadge.radius.toPx(), ring.minDimension / 2)
    val path = Path().apply { addRoundRect(RoundRect(ring, CornerRadius(radius))) }
    when (agent.activity) {
        BlueprintAgent.Activity.Live -> {
            drawPath(path, tone.copy(alpha = MachineSignal.AgentBadge.trackAlpha), style = Stroke(stroke))
            val length = PathMeasure().apply { setPath(path, true) }.length
            drawPath(path, tone, style = Stroke(stroke, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(length * MachineSignal.AgentBadge.sweep,
                    length * (1 - MachineSignal.AgentBadge.sweep)), -phase * length)))
        }
        BlueprintAgent.Activity.Assigned, BlueprintAgent.Activity.Claimed -> drawPath(path, tone, style = Stroke(stroke))
        BlueprintAgent.Activity.Stale -> {
            val measure = PathMeasure().apply { setPath(path, true) }
            val open = Path()
            measure.getSegment(0f, measure.length - MachineSignal.AgentBadge.dash.toPx(), open, true)
            drawPath(open, tone, style = Stroke(stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(MachineSignal.AgentBadge.dash.toPx(), MachineSignal.AgentBadge.dash.toPx()))))
        }
        else -> Unit
    }
    val side = min(MachineSignal.AgentBadge.iconSize.toPx(), bounds.minDimension - stroke * 2).roundToInt().coerceAtLeast(1)
    val origin = IntOffset((bounds.center.x - side / 2f).roundToInt(), (bounds.center.y - side / 2f).roundToInt())
    if (agent.image != null) drawImage(agent.image, dstOffset = origin, dstSize = IntSize(side, side), filterQuality = FilterQuality.High)
    else drawRoundRect(tone, Offset(origin.x.toFloat(), origin.y.toFloat()), androidx.compose.ui.geometry.Size(side.toFloat(), side.toFloat()),
        CornerRadius(radius / 2), style = Stroke(MachineSignal.Stroke.hairline.toPx()))
}
