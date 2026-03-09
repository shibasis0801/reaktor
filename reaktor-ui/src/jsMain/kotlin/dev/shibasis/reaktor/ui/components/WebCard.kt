package dev.shibasis.reaktor.ui.components

import dev.shibasis.reaktor.ui.tokens.WebDesignTokens
import js.objects.jso
import react.*
import react.dom.aria.AriaRole
import react.dom.html.ReactHTML.div
import web.cssom.*
import kotlin.js.JsExport

/**
 * Card Component (Kotlin/JS React)
 *
 * Container component for grouping related content.
 * Mirrors the Compose RCard API.
 */

external interface RCardProps : PropsWithChildren {
    var tokens: WebDesignTokens
    var variant: ComponentVariant?
    var onClick: (() -> Unit)?
    var containerColor: String?
    var contentColor: String?
    var padding: String?
}

val RCard = FC<RCardProps> { props ->
    val tokens = props.tokens
    val colors = tokens.colors
    val shapes = tokens.shapes
    val elevation = tokens.elevation
    val spacing = tokens.spacing

    val variant = props.variant ?: ComponentVariant.Filled
    val isClickable = props.onClick != null

    // Variant-based styling
    val containerColor = props.containerColor ?: when (variant) {
        ComponentVariant.Filled -> colors.surfaceContainer
        ComponentVariant.Elevated -> colors.surface
        ComponentVariant.Outlined -> colors.surface
        ComponentVariant.Tonal -> colors.secondaryContainer
        ComponentVariant.Text -> "transparent"
    }

    val contentColor = props.contentColor ?: when (variant) {
        ComponentVariant.Tonal -> colors.onSecondaryContainer
        else -> colors.onSurface
    }

    val shadow = when (variant) {
        ComponentVariant.Elevated -> elevation.sm
        else -> "none"
    }

    val border = when (variant) {
        ComponentVariant.Outlined -> "1px solid ${colors.outlineVariant}"
        else -> "none"
    }

    div {
        onClick = { props.onClick?.invoke() }

        style = jso {
            backgroundColor = containerColor.unsafeCast<BackgroundColor>()
            color = contentColor.unsafeCast<Color>()
            borderRadius = shapes.md.unsafeCast<BorderRadius>()
            boxShadow = shadow.unsafeCast<BoxShadow>()
            this.border = border.unsafeCast<Border>()
            overflow = Overflow.hidden
            cursor = if (isClickable) Cursor.pointer else Cursor.default
            padding = (props.padding ?: spacing.md).unsafeCast<Padding>()
        }

        if (isClickable) {
            role = AriaRole.button
            tabIndex = 0
        }

        props.children?.let { +it }
    }
}

// Convenience components
val RElevatedCard = FC<RCardProps> { props ->
    RCard {
        +props
        variant = ComponentVariant.Elevated
    }
}

val ROutlinedCard = FC<RCardProps> { props ->
    RCard {
        +props
        variant = ComponentVariant.Outlined
    }
}

val RFilledCard = FC<RCardProps> { props ->
    RCard {
        +props
        variant = ComponentVariant.Filled
    }
}
