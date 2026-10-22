package dev.shibasis.reaktor.blueprint

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.shibasis.reaktor.surface.compose.TooltipAppearance

data class BlueprintMark(val text: String, val color: Color, val kind: Kind = Kind.Tag) {
    enum class Kind { Tag, Badge, Pill, Dot }
}

data class BlueprintPinLook(val color: Color, val text: String, val textColor: Color, val filled: Boolean, val chip: Boolean = false, val selected: Boolean = false)

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
)

data class BlueprintFrameLook(
    val icon: ImageVector,
    val count: String,
    val lit: Color? = null,
    val marks: List<BlueprintMark> = emptyList(),
    val tag: String? = null,
    val muted: Boolean = false,
)

class BlueprintTip(val appearance: TooltipAppearance? = null, val content: @Composable () -> Unit)
