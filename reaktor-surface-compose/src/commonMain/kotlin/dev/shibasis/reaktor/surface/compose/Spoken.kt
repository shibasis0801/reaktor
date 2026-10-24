package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role

val SpokenRoles: Map<String, Role> = mapOf(
    "Button" to Role.Button, "Checkbox" to Role.Checkbox, "Switch" to Role.Switch, "RadioButton" to Role.RadioButton, "Tab" to Role.Tab,
    "Image" to Role.Image, "DropdownList" to Role.DropdownList, "ValuePicker" to Role.ValuePicker, "Carousel" to Role.Carousel,
)

fun Modifier.spoken(label: String? = null, role: Role? = null, heading: Boolean = false, hidden: Boolean = false, grouped: Boolean = false): Modifier =
    this then SpokenElement(label, role, heading, hidden, grouped)

private data class SpokenElement(val label: String?, val role: Role?, val heading: Boolean, val hidden: Boolean, val grouped: Boolean) : ModifierNodeElement<SpokenNode>() {
    override fun create(): SpokenNode = SpokenNode(this)

    override fun update(node: SpokenNode) {
        node.spec = this
        node.invalidateSemantics()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "spoken"
        properties["label"] = label
        properties["role"] = role
        properties["heading"] = heading
        properties["hidden"] = hidden
        properties["grouped"] = grouped
    }
}

private class SpokenNode(var spec: SpokenElement) : Modifier.Node(), SemanticsModifierNode {
    override val shouldMergeDescendantSemantics: Boolean get() = spec.grouped

    override val shouldClearDescendantSemantics: Boolean get() = spec.hidden

    override fun SemanticsPropertyReceiver.applySemantics() {
        if (spec.hidden) return
        spec.label?.let { contentDescription = it }
        spec.role?.let { role = it }
        if (spec.heading) heading()
    }
}
