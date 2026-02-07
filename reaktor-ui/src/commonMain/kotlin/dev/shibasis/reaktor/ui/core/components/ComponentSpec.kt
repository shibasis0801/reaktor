package dev.shibasis.reaktor.ui.core.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import dev.shibasis.reaktor.ui.core.tokens.TextStyleTokens

/**
 * Component Specifications
 *
 * Platform-agnostic component interfaces that can be implemented by:
 * - Compose Multiplatform (Android, iOS, Desktop)
 * - React/TypeScript (Web)
 *
 * These specifications define WHAT a component does, not HOW it does it.
 * Each platform implements these specs using its native UI toolkit.
 */

// ============================================================================
// COMMON TYPES
// ============================================================================

enum class ComponentSize {
    Small,
    Medium,
    Large
}

enum class ComponentVariant {
    Filled,      // Solid background
    Outlined,    // Border only
    Text,        // No background or border
    Tonal,       // Subtle background
    Elevated     // With shadow/elevation
}

enum class ComponentState {
    Enabled,
    Disabled,
    Loading,
    Error,
    Success
}

// ============================================================================
// BUTTON SPEC
// ============================================================================

data class ButtonSpec(
    val label: String? = null,
    val icon: ImageVector? = null,
    val iconPosition: IconPosition = IconPosition.Start,
    val size: ComponentSize = ComponentSize.Medium,
    val variant: ComponentVariant = ComponentVariant.Filled,
    val state: ComponentState = ComponentState.Enabled,
    val fullWidth: Boolean = false,
) {
    enum class IconPosition { Start, End, Only }
}

// ============================================================================
// TEXT SPEC
// ============================================================================

data class TextSpec(
    val text: String,
    val style: TextRole = TextRole.Body,
    val size: TextSize = TextSize.Medium,
    val color: Color? = null,
    val maxLines: Int = Int.MAX_VALUE,
    val overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    enum class TextRole {
        Display,
        Headline,
        Title,
        Body,
        Label,
        Caption
    }

    enum class TextSize {
        Small,
        Medium,
        Large
    }

    enum class TextOverflow {
        Clip,
        Ellipsis,
        Visible
    }
}

// ============================================================================
// INPUT SPEC
// ============================================================================

data class InputSpec(
    val value: String = "",
    val placeholder: String = "",
    val label: String? = null,
    val helperText: String? = null,
    val errorText: String? = null,
    val leadingIcon: ImageVector? = null,
    val trailingIcon: ImageVector? = null,
    val size: ComponentSize = ComponentSize.Medium,
    val variant: InputVariant = InputVariant.Outlined,
    val state: ComponentState = ComponentState.Enabled,
    val inputType: InputType = InputType.Text,
    val maxLines: Int = 1,
    val maxLength: Int? = null,
) {
    enum class InputVariant {
        Outlined,
        Filled,
        Underlined
    }

    enum class InputType {
        Text,
        Password,
        Email,
        Number,
        Phone,
        Url,
        Search,
        Multiline
    }
}

// ============================================================================
// ICON SPEC
// ============================================================================

data class IconSpec(
    val icon: ImageVector,
    val size: ComponentSize = ComponentSize.Medium,
    val color: Color? = null,
    val contentDescription: String? = null,
)

// ============================================================================
// CARD SPEC
// ============================================================================

data class CardSpec(
    val variant: ComponentVariant = ComponentVariant.Elevated,
    val clickable: Boolean = false,
)

// ============================================================================
// AVATAR SPEC
// ============================================================================

data class AvatarSpec(
    val imageUrl: String? = null,
    val initials: String? = null,
    val icon: ImageVector? = null,
    val size: ComponentSize = ComponentSize.Medium,
)

// ============================================================================
// BADGE SPEC
// ============================================================================

data class BadgeSpec(
    val count: Int? = null,
    val showDot: Boolean = false,
    val maxCount: Int = 99,
    val color: BadgeColor = BadgeColor.Error,
) {
    enum class BadgeColor {
        Primary,
        Secondary,
        Error,
        Success,
        Warning,
        Info
    }
}

// ============================================================================
// CHIP SPEC
// ============================================================================

data class ChipSpec(
    val label: String,
    val leadingIcon: ImageVector? = null,
    val trailingIcon: ImageVector? = null,
    val selected: Boolean = false,
    val variant: ChipVariant = ChipVariant.Assist,
    val state: ComponentState = ComponentState.Enabled,
) {
    enum class ChipVariant {
        Assist,     // Help/suggestion
        Filter,     // Toggle filter
        Input,      // User input (like tags)
        Suggestion  // Quick action
    }
}

// ============================================================================
// DIVIDER SPEC
// ============================================================================

data class DividerSpec(
    val orientation: Orientation = Orientation.Horizontal,
    val thickness: Dp? = null,
    val color: Color? = null,
) {
    enum class Orientation {
        Horizontal,
        Vertical
    }
}

// ============================================================================
// PROGRESS SPEC
// ============================================================================

data class ProgressSpec(
    val type: ProgressType = ProgressType.Circular,
    val value: Float? = null, // null = indeterminate
    val size: ComponentSize = ComponentSize.Medium,
    val color: Color? = null,
) {
    enum class ProgressType {
        Circular,
        Linear
    }
}

// ============================================================================
// SWITCH SPEC
// ============================================================================

data class SwitchSpec(
    val checked: Boolean = false,
    val state: ComponentState = ComponentState.Enabled,
    val thumbIcon: ImageVector? = null,
)

// ============================================================================
// CHECKBOX SPEC
// ============================================================================

data class CheckboxSpec(
    val checked: Boolean = false,
    val indeterminate: Boolean = false,
    val state: ComponentState = ComponentState.Enabled,
)

// ============================================================================
// RADIO SPEC
// ============================================================================

data class RadioSpec(
    val selected: Boolean = false,
    val state: ComponentState = ComponentState.Enabled,
)

// ============================================================================
// SLIDER SPEC
// ============================================================================

data class SliderSpec(
    val value: Float = 0f,
    val range: ClosedFloatingPointRange<Float> = 0f..1f,
    val steps: Int = 0, // 0 = continuous
    val state: ComponentState = ComponentState.Enabled,
)

// ============================================================================
// TOOLTIP SPEC
// ============================================================================

data class TooltipSpec(
    val text: String,
    val position: TooltipPosition = TooltipPosition.Bottom,
) {
    enum class TooltipPosition {
        Top,
        Bottom,
        Start,
        End
    }
}
