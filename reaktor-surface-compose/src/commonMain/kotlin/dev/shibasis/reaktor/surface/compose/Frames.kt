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
) = Studied("Bar", modifier, appearance, Appearance.Bar, listOf("content")) { edits, chosen ->
    Passive(edits.modifier) { theme, feedback -> chosen.Content(Unit, Unit, theme, feedback, BarSlots(edits.scoped("content", content))) }
}

@Composable
fun Section(
    heading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    appearance: SectionAppearance = LocalAppearances.current[Appearance.Section],
    content: @Composable ColumnScope.() -> Unit,
) = Studied("Section", modifier, appearance, Appearance.Section, listOf("heading", "trailing", "content")) { edits, chosen ->
    Passive(edits.modifier) { theme, feedback -> chosen.Content(Unit, Unit, theme, feedback, SectionSlots(edits.slot("heading", heading), edits.optional("trailing", trailing), edits.scoped("content", content))) }
}

@Composable
fun Property(
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    appearance: PropertyAppearance = LocalAppearances.current[Appearance.Property],
    value: @Composable RowScope.() -> Unit,
) = Studied("Property", modifier, appearance, Appearance.Property, listOf("label", "value")) { edits, chosen ->
    Passive(edits.modifier) { theme, feedback -> chosen.Content(Unit, Unit, theme, feedback, PropertySlots(edits.slot("label", label), edits.scoped("value", value))) }
}

@Composable
fun Metric(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trend: (@Composable () -> Unit)? = null,
    detail: (@Composable () -> Unit)? = null,
    appearance: MetricAppearance = LocalAppearances.current[Appearance.Metric],
) = Studied("Metric", modifier, appearance, Appearance.Metric, listOf("label", "value", "trend", "detail")) { edits, chosen ->
    Passive(edits.modifier) { theme, feedback -> chosen.Content(Unit, Unit, theme, feedback, MetricSlots(edits.slot("label", label), edits.slot("value", value), edits.optional("trend", trend), edits.optional("detail", detail))) }
}

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
) = Studied("Finding", modifier, appearance, Appearance.Finding, listOf("mark", "title", "aside", "detail", "evidence", "fix")) { edits, chosen ->
    Passive(edits.modifier) { theme, feedback ->
        chosen.Content(FindingProperties(selected), Unit, theme, feedback,
            FindingSlots(edits.slot("mark", mark), edits.slot("title", title), edits.optional("aside", aside), edits.optional("detail", detail), edits.optional("evidence", evidence), edits.optional("fix", fix)))
    }
}

@Composable
private fun <A : Any> Studied(kind: String, modifier: Modifier, appearance: A, key: AppearanceKey<A>, slots: List<String>, content: @Composable (SurfaceEdits, A) -> Unit) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) content(Unedited(modifier), appearance)
    else studio.Element(SurfaceCall(kind, modifier, appearance = appearance, appearanceKey = key, slots = slots)) { edits -> content(edits, edits.appearance(appearance)) }
}

@Composable
private fun Passive(modifier: Modifier, content: @Composable (ThemeSnapshot, ComposeFeedback) -> Unit) =
    Box(modifier, propagateMinConstraints = true) { content(LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false)) }

internal class Unedited(override val modifier: Modifier) : SurfaceEdits {
    override fun value(field: String): String? = null
    override fun <A : Any> appearance(original: A): A = original
    override fun action(name: String, original: () -> Unit): () -> Unit = original
    override fun <T> handler(name: String, original: (T) -> Unit): (T) -> Unit = original
    override fun slot(name: String, content: @Composable () -> Unit): @Composable () -> Unit = content
    override fun <S> scoped(name: String, content: @Composable S.() -> Unit): @Composable S.() -> Unit = content
}

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
