package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.surface.ThemeSnapshot

class BarSlots(val content: @Composable RowScope.() -> Unit)

typealias BarAppearance = ComposeAppearance<Unit, Unit, BarSlots>

class SectionSlots(val heading: @Composable () -> Unit, val trailing: (@Composable () -> Unit)?, val content: @Composable ColumnScope.() -> Unit)

typealias SectionAppearance = ComposeAppearance<Unit, Unit, SectionSlots>

class PropertySlots(val label: @Composable () -> Unit, val value: @Composable RowScope.() -> Unit)

typealias PropertyAppearance = ComposeAppearance<Unit, Unit, PropertySlots>

class MetricSlots(
    val label: @Composable () -> Unit,
    val value: @Composable () -> Unit,
    val trend: (@Composable () -> Unit)?,
    val detail: (@Composable () -> Unit)?,
)

typealias MetricAppearance = ComposeAppearance<Unit, Unit, MetricSlots>

data class FindingProperties(val selected: Boolean)

class FindingSlots(
    val mark: @Composable () -> Unit,
    val title: @Composable () -> Unit,
    val aside: (@Composable () -> Unit)?,
    val detail: (@Composable () -> Unit)?,
    val evidence: (@Composable () -> Unit)?,
    val fix: (@Composable () -> Unit)?,
)

typealias FindingAppearance = ComposeAppearance<FindingProperties, Unit, FindingSlots>

@Composable
fun Bar(
    modifier: Modifier = Modifier,
    appearance: BarAppearance = LocalAppearances.current[Appearance.Bar],
    content: @Composable RowScope.() -> Unit,
) = Passive(modifier) { theme, feedback -> appearance.Content(Unit, Unit, theme, feedback, BarSlots(content)) }

@Composable
fun Section(
    heading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    appearance: SectionAppearance = LocalAppearances.current[Appearance.Section],
    content: @Composable ColumnScope.() -> Unit,
) = Passive(modifier) { theme, feedback -> appearance.Content(Unit, Unit, theme, feedback, SectionSlots(heading, trailing, content)) }

@Composable
fun Property(
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    appearance: PropertyAppearance = LocalAppearances.current[Appearance.Property],
    value: @Composable RowScope.() -> Unit,
) = Passive(modifier) { theme, feedback -> appearance.Content(Unit, Unit, theme, feedback, PropertySlots(label, value)) }

@Composable
fun Metric(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trend: (@Composable () -> Unit)? = null,
    detail: (@Composable () -> Unit)? = null,
    appearance: MetricAppearance = LocalAppearances.current[Appearance.Metric],
) = Passive(modifier) { theme, feedback -> appearance.Content(Unit, Unit, theme, feedback, MetricSlots(label, value, trend, detail)) }

@Composable
fun Finding(
    mark: @Composable () -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    aside: (@Composable () -> Unit)? = null,
    detail: (@Composable () -> Unit)? = null,
    evidence: (@Composable () -> Unit)? = null,
    fix: (@Composable () -> Unit)? = null,
    appearance: FindingAppearance = LocalAppearances.current[Appearance.Finding],
) = Passive(modifier) { theme, feedback ->
    appearance.Content(FindingProperties(selected), Unit, theme, feedback, FindingSlots(mark, title, aside, detail, evidence, fix))
}

@Composable
private fun Passive(modifier: Modifier, content: @Composable (ThemeSnapshot, ComposeFeedback) -> Unit) =
    Box(modifier, propagateMinConstraints = true) { content(LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false)) }

val BareBar: BarAppearance = object : BarAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BarSlots) =
        Row(verticalAlignment = Alignment.CenterVertically, content = slots.content)
}

val BareSection: SectionAppearance = object : SectionAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: SectionSlots) = Column {
        Row {
            Box(Modifier.weight(1f)) { slots.heading() }
            slots.trailing?.invoke()
        }
        slots.content(this)
    }
}

val BareProperty: PropertyAppearance = object : PropertyAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PropertySlots) = Row {
        slots.label()
        slots.value(this)
    }
}

val BareMetric: MetricAppearance = object : MetricAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: MetricSlots) = Column {
        slots.label()
        slots.value()
        slots.trend?.invoke()
        slots.detail?.invoke()
    }
}

val BareFinding: FindingAppearance = object : FindingAppearance {
    @Composable
    override fun Content(properties: FindingProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: FindingSlots) = Column {
        Row {
            slots.mark()
            slots.aside?.invoke()
        }
        slots.title()
        slots.detail?.invoke()
        slots.evidence?.invoke()
        slots.fix?.invoke()
    }
}
