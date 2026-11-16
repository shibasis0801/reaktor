package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.blueprint.canvas.LinkStyle

data class BlueprintOverlay(val id: String, val label: String, val color: Color, val targets: Set<String>, val glow: Boolean = false)

data class BlueprintOverlayWire(val id: String, val from: String, val to: String, val label: String, val color: Color)

data class BlueprintOverlays(val layers: List<BlueprintOverlay> = emptyList(), val wires: List<BlueprintOverlayWire> = emptyList()) {
    fun <K> prepare(layout: BlueprintLayout<K>): BlueprintOverlayPaint {
        val cards = linkedMapOf<String, List<BlueprintOverlay>>()
        val rows = linkedMapOf<String, List<BlueprintOverlay>>()
        layout.cards.values.forEach { card ->
            val direct = layers.filter { card.id in it.targets }
            val matched = card.pins.associate { pin ->
                pin.id to layers.filter { layer -> pin.id in layer.targets || pin.key in layer.targets || pin.aliases.any { it in layer.targets } }
            }
            matched.filterValues { it.isNotEmpty() }.forEach { (id, marks) -> rows[id] = marks }
            val all = (direct + matched.values.flatten()).distinctBy { it.id }
            if (all.isNotEmpty()) cards[card.id] = all
        }
        val ends = linkedMapOf<String, Pair<String, String?>>()
        layout.cards.values.forEach { card ->
            ends[card.id] = Pair(card.id, null)
            card.pins.forEach { pin -> (listOf(pin.id, pin.key) + pin.aliases).forEach { ends[it] = Pair(card.id, pin.key) } }
        }
        val routed = wires.mapNotNull { wire ->
            val a = ends[wire.from] ?: return@mapNotNull null
            val b = ends[wire.to] ?: return@mapNotNull null
            if (a == b) return@mapNotNull null
            val points = BlueprintEngine.bezier(BlueprintEngine.anchor(layout.cards, a.first, a.second, true), BlueprintEngine.anchor(layout.cards, b.first, b.second, false))
            Link(wire.id, a.first, b.first, a.second, b.second, LinkKind.Wire, listOf(wire.id), points, family = "overlay", curve = true)
        }
        return BlueprintOverlayPaint(cards, rows, routed, wires.associateBy { it.id })
    }
}

class BlueprintOverlayPaint internal constructor(
    private val cards: Map<String, List<BlueprintOverlay>>,
    private val rows: Map<String, List<BlueprintOverlay>>,
    private val links: List<Link>,
    private val wires: Map<String, BlueprintOverlayWire>,
) {
    fun <K> layout(base: BlueprintLayout<K>): BlueprintLayout<K> = if (links.isEmpty()) base else base.copy(links = base.links + links)
    fun style(id: String): LinkStyle? = wires[id]?.let { LinkStyle(it.color, dash = 1.5f to 4.5f) }
    val labels: Map<String, String> get() = wires.mapValues { it.value.label }

    fun glow(id: String): Color? = cards[id]?.firstOrNull { it.glow }?.color

    fun decorate(card: Card, look: BlueprintCardLook): BlueprintCardLook {
        val layers = cards[card.id].orEmpty()
        if (layers.isEmpty()) return look
        fun BlueprintOverlay.mark() = BlueprintMark(label, color, BlueprintMark.Kind.Dot)
        fun pin(id: String, content: BlueprintPinLook): BlueprintPinLook {
            val shown = rows[id].orEmpty()
            return if (shown.isEmpty()) content else content.copy(color = shown.last().color, filled = true, marks = content.marks + shown.map { it.mark() })
        }
        return look.copy(
            marks = look.marks + layers.map { it.mark() },
            pins = look.pins.mapIndexed { index, content -> content?.let { pin(card.pins.getOrNull(index)?.id.orEmpty(), it) } },
            rows = look.rows.map { it.copy(content = pin(it.id, it.content)) },
        )
    }
}

val LocalBlueprintOverlays = staticCompositionLocalOf { BlueprintOverlays() }
