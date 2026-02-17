package dev.shibasis.reaktor.ui.components

import dev.shibasis.reaktor.ui.tokens.WebDesignTokens
import js.objects.jso
import react.*
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.span
import web.cssom.*
import kotlin.js.JsExport

/**
 * Button Component (Kotlin/JS React)
 *
 * Primary interactive element for user actions.
 * Mirrors the Compose RButton API.
 */

@JsExport
enum class ComponentSize { Small, Medium, Large }

@JsExport
enum class ComponentVariant { Filled, Outlined, Text, Tonal, Elevated }

@JsExport
enum class ComponentState { Enabled, Disabled, Loading }

external interface RButtonProps : Props {
    var tokens: WebDesignTokens
    var label: String?
    var icon: ReactNode?
    var size: ComponentSize?
    var variant: ComponentVariant?
    var state: ComponentState?
    var fullWidth: Boolean?
    var onClick: (() -> Unit)?
}

val RButton = FC<RButtonProps> { props ->
    val tokens = props.tokens
    val colors = tokens.colors
    val spacing = tokens.spacing
    val shapes = tokens.shapes
    val sizing = tokens.sizing

    val size = props.size ?: ComponentSize.Medium
    val variant = props.variant ?: ComponentVariant.Filled
    val state = props.state ?: ComponentState.Enabled
    val fullWidth = props.fullWidth ?: false

    val isDisabled = state == ComponentState.Disabled
    val isLoading = state == ComponentState.Loading

    // Size-based styling
    val height = when (size) {
        ComponentSize.Small -> sizing.buttonSm
        ComponentSize.Medium -> sizing.buttonMd
        ComponentSize.Large -> sizing.buttonLg
    }

    val padding = when (size) {
        ComponentSize.Small -> spacing.sm
        ComponentSize.Medium -> spacing.md
        ComponentSize.Large -> spacing.lg
    }

    val iconSize = when (size) {
        ComponentSize.Small -> sizing.iconXs
        ComponentSize.Medium -> sizing.iconSm
        ComponentSize.Large -> sizing.iconMd
    }

    // Variant-based colors
    val (bgColor, textColor, borderColor) = when {
        isDisabled -> Triple("rgba(0,0,0,0.12)", "rgba(0,0,0,0.38)", "transparent")
        else -> when (variant) {
            ComponentVariant.Filled -> Triple(colors.primary, colors.onPrimary, "transparent")
            ComponentVariant.Tonal -> Triple(colors.secondaryContainer, colors.onSecondaryContainer, "transparent")
            ComponentVariant.Elevated -> Triple(colors.surface, colors.primary, "transparent")
            ComponentVariant.Outlined -> Triple("transparent", colors.primary, colors.outline)
            ComponentVariant.Text -> Triple("transparent", colors.primary, "transparent")
        }
    }

    button {
        disabled = isDisabled || isLoading
        onClick = { props.onClick?.invoke() }

        style = jso {
            display = Display.inlineFlex
            alignItems = AlignItems.center
            justifyContent = JustifyContent.center
            gap = spacing.xs.unsafeCast<Gap>()
            this.height = height.unsafeCast<Height>()
            paddingLeft = padding.unsafeCast<PaddingLeft>()
            paddingRight = padding.unsafeCast<PaddingRight>()
            paddingTop = 0.px
            paddingBottom = 0.px
            backgroundColor = bgColor.unsafeCast<BackgroundColor>()
            color = textColor.unsafeCast<Color>()
            border = if (borderColor == "transparent") None.none else Border(1.px, LineStyle.solid, borderColor.unsafeCast<Color>())
            borderRadius = shapes.md.unsafeCast<BorderRadius>()
            cursor = if (isDisabled || isLoading) Cursor.notAllowed else Cursor.pointer
            opacity = if (isLoading) number(0.7) else number(1.0)
            fontWeight = integer(500)
            fontSize = FontSize.inherit
            fontFamily = FontFamily.inherit
            textTransform = None.none
            width = if (fullWidth) 100.pct else Auto.auto
            boxShadow = if (variant == ComponentVariant.Elevated) tokens.elevation.sm.unsafeCast<BoxShadow>() else None.none
        }

        if (isLoading) {
            span {
                style = jso {
                    width = iconSize.unsafeCast<Width>()
                    height = iconSize.unsafeCast<Height>()
                    border = Border(2.px, LineStyle.solid, textColor.unsafeCast<Color>())
                    borderTopColor = NamedColor.transparent
                    borderRadius = 50.pct
                    // Animation would need CSS keyframes
                }
            }
        } else {
            props.icon?.let { icon ->
                span {
                    style = jso {
                        width = iconSize.unsafeCast<Width>()
                        height = iconSize.unsafeCast<Height>()
                        display = Display.flex
                        alignItems = AlignItems.center
                        justifyContent = JustifyContent.center
                    }
                    +icon
                }
            }
            props.label?.let { label ->
                +label
            }
        }
    }
}
