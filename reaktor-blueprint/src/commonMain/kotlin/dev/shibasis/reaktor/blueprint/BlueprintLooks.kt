package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.surface.compose.TooltipAppearance

enum class BlueprintTitle { Whole, Words, Hidden }

enum class BlueprintTier { Overview, Middle, Detail }

data class BlueprintReading(
    val level: Int, val smallest: Double, val titles: Map<BlueprintTitle, Int>, val tiers: Map<BlueprintTier, Int> = emptyMap(),
    val legible: Int = 0, val groups: Int = 0, val named: Int = 0, val unnamed: List<String> = emptyList(),
) {
    operator fun get(title: BlueprintTitle): Int = titles[title] ?: 0
    operator fun get(tier: BlueprintTier): Int = tiers[tier] ?: 0
}

enum class BlueprintFace {
    Code, Prose;

    companion object {
        const val Key = "face"
        const val ProseRows = 5

        fun of(node: NodeShape): BlueprintFace = entries.firstOrNull { it.name.equals(node.attributes[Key], ignoreCase = true) } ?: Code

        fun rows(node: NodeShape, pinned: Int): Int = if (of(node) == Prose) maxOf(pinned, ProseRows) else pinned
    }
}

data class BlueprintMark(val text: String, val color: Color, val kind: Kind = Kind.Tag) {
    enum class Kind { Tag, Badge, Pill, Dot }
}

data class BlueprintChoice(val checked: Boolean, val tag: String, val change: (Boolean) -> Unit)

data class BlueprintPinLook(val color: Color, val text: String, val textColor: Color, val filled: Boolean, val chip: Boolean = false, val selected: Boolean = false, val marks: List<BlueprintMark> = emptyList(), val tag: String? = null, val choice: BlueprintChoice? = null, val icon: ImageVector? = null)

data class BlueprintRowLook(val id: String, val content: BlueprintPinLook, val onClick: (() -> Unit)? = null, val trailingCells: List<BlueprintPinLook> = emptyList())

data class BlueprintCardAction(val id: String, val label: String, val icon: ImageVector, val onInvoke: () -> Unit)

data class BlueprintCardLook(
    val tone: Color,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val subtitleMono: Boolean = false,
    val selected: Boolean = false,
    val outline: Color? = null,
    val folded: String? = null,
    val marks: List<BlueprintMark> = emptyList(),
    val pins: List<BlueprintPinLook?> = emptyList(),
    val tag: String? = null,
    val action: String? = null,
    val image: androidx.compose.ui.graphics.ImageBitmap? = null,
    val imageDescription: String? = null,
    val imageHighlight: androidx.compose.ui.geometry.Rect? = null,
    val muted: Boolean = false,
    val choice: BlueprintChoice? = null,
    val face: BlueprintFace = BlueprintFace.Code,
    val eyebrow: String? = null,
    val progress: Float? = null,
    val agent: BlueprintAgent? = null,
    val actions: List<BlueprintCardAction> = emptyList(),
    val rows: List<BlueprintRowLook> = emptyList(),
    val titleContent: (@Composable (TextStyle) -> Unit)? = null,
    val markContent: Map<BlueprintMark, @Composable () -> Unit> = emptyMap(),
) {
    internal val hasHeaderContent: Boolean get() = titleContent != null || markContent.isNotEmpty()
    val drawsControls: Boolean get() = hasHeaderContent || choice != null || actions.isNotEmpty() || pins.any { it?.choice != null } || rows.any { it.onClick != null || it.content.choice != null }
}

data class BlueprintFrameLook(
    val icon: ImageVector,
    val count: String,
    val lit: Color? = null,
    val marks: List<BlueprintMark> = emptyList(),
    val tag: String? = null,
    val muted: Boolean = false,
    val selected: Boolean = false,
    val titleContent: (@Composable (TextStyle) -> Unit)? = null,
    val markContent: Map<BlueprintMark, @Composable () -> Unit> = emptyMap(),
) {
    internal val hasHeaderContent: Boolean get() = titleContent != null || markContent.isNotEmpty()
}

class BlueprintTip(val appearance: TooltipAppearance? = null, val content: @Composable () -> Unit)
