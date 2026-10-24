package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.surface.ComponentContract
import dev.shibasis.reaktor.surface.SlotKinds
import dev.shibasis.reaktor.surface.SlotSchema

@Composable
fun Component(contract: ComponentContract, modifier: Modifier, values: Map<String, String?>, content: @Composable (SurfaceEdits) -> Unit) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) content(Unedited(modifier))
    else studio.Element(SurfaceCall(contract.kind, modifier,
        contract.fields.map { schema -> SurfaceField(schema.name, schema.type, values[schema.name] ?: schema.default, choices = schema.choices) },
        slots = contract.slots.map { it.name }, contract = contract), content)
}

val SurfaceContracts: Map<String, ComponentContract> = listOf(
    ComponentContract("surface.button", "Button", events = listOf("onClick"), slots = listOf(SlotSchema("content", SlotKinds.Label, max = 3))),
    ComponentContract("surface.badge", "Badge", slots = listOf(SlotSchema("content", SlotKinds.Label, max = 2))),
    ComponentContract("surface.checkbox", "Checkbox", events = listOf("onCheckedChange"), slots = listOf(SlotSchema("content", SlotKinds.Label, max = 2))),
    ComponentContract("surface.switch", "Switch", events = listOf("onCheckedChange"), slots = listOf(SlotSchema("content", SlotKinds.Label, max = 2))),
    ComponentContract("surface.list-row", "ListRow", events = listOf("onClick"), slots = listOf(
        SlotSchema("headline", SlotKinds.Label, max = 2), SlotSchema("supporting", SlotKinds.Label, max = 2),
        SlotSchema("leading", SlotKinds.Glyph + SlotKinds.Status, max = 1), SlotSchema("trailing", SlotKinds.Controls, max = 2))),
    ComponentContract("surface.section", "Section", slots = listOf(SlotSchema("heading", SlotKinds.Label, max = 2), SlotSchema("trailing", SlotKinds.Controls, max = 2), SlotSchema("content"))),
    ComponentContract("surface.bar", "Bar", slots = listOf(SlotSchema("content", SlotKinds.Controls))),
    ComponentContract("surface.property", "Property", slots = listOf(SlotSchema("label", SlotKinds.Label, max = 2), SlotSchema("value"))),
    ComponentContract("surface.metric", "Metric", slots = listOf(SlotSchema("label", SlotKinds.Label, max = 2), SlotSchema("value", SlotKinds.Status, max = 2),
        SlotSchema("trend", SlotKinds.Status, max = 1), SlotSchema("detail", SlotKinds.Label, max = 2))),
    ComponentContract("surface.finding", "Finding", slots = listOf(SlotSchema("mark", SlotKinds.Glyph + SlotKinds.Status, max = 1), SlotSchema("title", SlotKinds.Label, max = 2),
        SlotSchema("aside", SlotKinds.Status), SlotSchema("detail"), SlotSchema("evidence"), SlotSchema("fix"))),
    ComponentContract("surface.state-view", "StateView", slots = listOf(SlotSchema("reason", SlotKinds.Label, max = 2), SlotSchema("action", setOf("Button"), max = 2))),
).associateBy { it.kind }
