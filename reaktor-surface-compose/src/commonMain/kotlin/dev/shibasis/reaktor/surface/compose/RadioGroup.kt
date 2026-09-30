package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.BehaviorKernel
import dev.shibasis.reaktor.surface.Choose
import dev.shibasis.reaktor.surface.Chosen
import dev.shibasis.reaktor.surface.OneOfKernel
import dev.shibasis.reaktor.surface.OneOfProperties
import dev.shibasis.reaktor.surface.OneOfState
import dev.shibasis.reaktor.surface.PartKey
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot

typealias OneOfBehavior = BehaviorKernel<OneOfProperties, OneOfState, Choose, Chosen>
typealias ItemAppearance = ComposeAppearance<ItemProperties, PressState, ItemSlots>

data class ItemProperties(val selected: Boolean, val enabled: Boolean)

class ItemSlots(val icon: (@Composable () -> Unit)?, val content: @Composable () -> Unit)

@Composable
fun RadioGroup(
    selected: String?,
    onSelectedChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: OneOfBehavior = OneOfKernel,
    content: @Composable OneOfScope.() -> Unit,
) = OneOf(selected, onSelectedChange, modifier, enabled, behavior, Role.RadioButton, LocalAppearances.current.radio, content)

@Composable
internal fun OneOf(
    selected: String?,
    onSelectedChange: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    behavior: OneOfBehavior,
    role: Role,
    appearance: ItemAppearance,
    content: @Composable OneOfScope.() -> Unit,
) {
    val group = rememberMachine(behavior, OneOfProperties(selected, enabled)) { onSelectedChange(it.key) }
    Box(modifier.selectableGroup(), propagateMinConstraints = true) {
        OneOfScope(group, selected, enabled, role, appearance).content()
    }
}

@Stable
class OneOfScope internal constructor(
    private val group: Machine<OneOfProperties, OneOfState, Choose, Chosen>,
    private val selected: String?,
    private val enabled: Boolean,
    private val role: Role,
    private val appearance: ItemAppearance,
) {
    @Composable
    fun Item(
        key: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        appearance: ItemAppearance = this.appearance,
        icon: (@Composable () -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        val properties = ItemProperties(selected == key, enabled && this.enabled)
        val press = rememberMachine(PressKernel, PressProperties(properties.enabled)) {}
        val source = remember { MutableInteractionSource() }
        source.feed(press)
        Box(
            modifier
                .part(group, PartKey(key))
                .selectable(properties.selected, source, indication = null, enabled = properties.enabled, role = role) {
                    group.send(Choose(key, group.nextSequence()))
                },
            propagateMinConstraints = true,
        ) {
            val state = press.state
            appearance.Content(properties, state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), ItemSlots(icon, content))
        }
    }
}

val BareRadio: ItemAppearance = object : ItemAppearance {
    @Composable
    override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
        Row(
            Modifier.defaultMinSize(minHeight = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp).clip(CircleShape).border(1.dp, Color.Gray, CircleShape), contentAlignment = Alignment.Center) {
                if (properties.selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.Black))
            }
            slots.content()
        }
    }
}
