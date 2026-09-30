package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.BehaviorKernel
import dev.shibasis.reaktor.surface.Choose
import dev.shibasis.reaktor.surface.ManyOfEvent
import dev.shibasis.reaktor.surface.ManyOfKernel
import dev.shibasis.reaktor.surface.ManyOfProperties
import dev.shibasis.reaktor.surface.ManyOfState
import dev.shibasis.reaktor.surface.PartKey
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot

typealias ManyOfBehavior = BehaviorKernel<ManyOfProperties, ManyOfState, Choose, ManyOfEvent>

@Composable
fun ToggleGroup(
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    capacity: Int = Int.MAX_VALUE,
    enabled: Boolean = true,
    onRefused: (String) -> Unit = {},
    behavior: ManyOfBehavior = ManyOfKernel,
    content: @Composable ToggleGroupScope.() -> Unit,
) {
    val group = rememberMachine(behavior, ManyOfProperties(selected, capacity, enabled)) { event ->
        when (event) {
            is ManyOfEvent.Change -> onSelectedChange(event.selected)
            is ManyOfEvent.Refused -> onRefused(event.key)
        }
    }
    Box(modifier, propagateMinConstraints = true) {
        ToggleGroupScope(group, selected, enabled).content()
    }
}

@Stable
class ToggleGroupScope internal constructor(
    private val group: Machine<ManyOfProperties, ManyOfState, Choose, ManyOfEvent>,
    private val selected: Set<String>,
    private val enabled: Boolean,
) {
    @Composable
    fun Item(
        key: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        appearance: ItemAppearance = LocalAppearances.current.chip,
        icon: (@Composable () -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        val properties = ItemProperties(key in selected, enabled && this.enabled)
        val press = rememberMachine(PressKernel, PressProperties(properties.enabled)) {}
        val source = remember { MutableInteractionSource() }
        source.feed(press)
        Box(
            modifier
                .part(group, PartKey(key))
                .toggleable(properties.selected, source, indication = null, enabled = properties.enabled, role = Role.Checkbox) {
                    group.send(Choose(key, group.nextSequence()))
                },
            propagateMinConstraints = true,
        ) {
            val state = press.state
            appearance.Content(properties, state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), ItemSlots(icon, content))
        }
    }
}

val BareChip: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        Box(
            Modifier
                .defaultMinSize(minHeight = 36.dp)
                .border(if (properties.selected) 2.dp else 1.dp, if (properties.selected) Color.Black else Color.Gray, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) { slots.content() }
    }
}
