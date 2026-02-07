package dev.shibasis.reaktor.ui.components

import dev.shibasis.reaktor.ui.tokens.WebDesignTokens
import dev.shibasis.reaktor.ui.tokens.WebTextStyle
import react.*
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.h5
import react.dom.html.ReactHTML.h6
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import web.cssom.*
import kotlin.js.JsExport

/**
 * Text Component (Kotlin/JS React)
 *
 * Typography component for displaying text content.
 * Mirrors the Compose RText API.
 */

@JsExport
enum class TextRole { Display, Headline, Title, Body, Label, Caption }

@JsExport
enum class TextSize { Small, Medium, Large }

external interface RTextProps : Props {
    var tokens: WebDesignTokens
    var text: String?
    var role: TextRole?
    var size: TextSize?
    var color: String?
    var maxLines: Int?
    var textAlign: String?
    var children: ReactNode?
}

val RText = FC<RTextProps> { props ->
    val tokens = props.tokens
    val colors = tokens.colors
    val typography = tokens.typography

    val role = props.role ?: TextRole.Body
    val size = props.size ?: TextSize.Medium

    // Get style tokens based on role and size
    val styleTokens: WebTextStyle = when (role) {
        TextRole.Display -> when (size) {
            TextSize.Small -> typography.displaySmall
            TextSize.Large -> typography.displayLarge
            TextSize.Medium -> typography.displayMedium
        }
        TextRole.Headline -> when (size) {
            TextSize.Small -> typography.headlineSmall
            TextSize.Large -> typography.headlineLarge
            TextSize.Medium -> typography.headlineMedium
        }
        TextRole.Title -> when (size) {
            TextSize.Small -> typography.titleSmall
            TextSize.Large -> typography.titleLarge
            TextSize.Medium -> typography.titleMedium
        }
        TextRole.Body -> when (size) {
            TextSize.Small -> typography.bodySmall
            TextSize.Large -> typography.bodyLarge
            TextSize.Medium -> typography.bodyMedium
        }
        TextRole.Label -> when (size) {
            TextSize.Small -> typography.labelSmall
            TextSize.Large -> typography.labelLarge
            TextSize.Medium -> typography.labelMedium
        }
        TextRole.Caption -> typography.bodySmall
    }

    // Get default color based on role
    val defaultColor = when (role) {
        TextRole.Display, TextRole.Headline -> colors.onBackground
        TextRole.Title, TextRole.Body -> colors.onSurface
        TextRole.Label, TextRole.Caption -> colors.onSurfaceVariant
    }

    val resolvedColor = props.color ?: defaultColor

    val textStyle: CSSProperties = jso {
        this.color = resolvedColor.unsafeCast<Color>()
        fontSize = styleTokens.fontSize.unsafeCast<FontSize>()
        lineHeight = styleTokens.lineHeight.unsafeCast<LineHeight>()
        fontWeight = styleTokens.fontWeight.toIntOrNull()?.let { integer(it) } ?: FontWeight.normal
        letterSpacing = styleTokens.letterSpacing.unsafeCast<LetterSpacing>()
        margin = 0.px

        props.textAlign?.let {
            textAlign = it.unsafeCast<TextAlign>()
        }

        props.maxLines?.let { lines ->
            if (lines > 0) {
                display = Display.webkitBox
                asDynamic().WebkitLineClamp = lines
                asDynamic().WebkitBoxOrient = "vertical"
                overflow = Overflow.hidden
                textOverflow = TextOverflow.ellipsis
            }
        }
    }

    val content = props.text ?: props.children

    // Use semantic HTML element based on role
    when (role) {
        TextRole.Display -> when (size) {
            TextSize.Large -> h1 { style = textStyle; +content.toString() }
            TextSize.Medium -> h2 { style = textStyle; +content.toString() }
            TextSize.Small -> h3 { style = textStyle; +content.toString() }
        }
        TextRole.Headline -> when (size) {
            TextSize.Large -> h2 { style = textStyle; +content.toString() }
            TextSize.Medium -> h3 { style = textStyle; +content.toString() }
            TextSize.Small -> h4 { style = textStyle; +content.toString() }
        }
        TextRole.Title -> when (size) {
            TextSize.Large -> h4 { style = textStyle; +content.toString() }
            TextSize.Medium -> h5 { style = textStyle; +content.toString() }
            TextSize.Small -> h6 { style = textStyle; +content.toString() }
        }
        TextRole.Body -> p { style = textStyle; +content.toString() }
        TextRole.Label, TextRole.Caption -> span { style = textStyle; +content.toString() }
    }
}

// Convenience components
val RDisplayText = FC<RTextProps> { props ->
    RText {
        +props
        role = TextRole.Display
    }
}

val RHeadline = FC<RTextProps> { props ->
    RText {
        +props
        role = TextRole.Headline
    }
}

val RTitle = FC<RTextProps> { props ->
    RText {
        +props
        role = TextRole.Title
    }
}

val RBody = FC<RTextProps> { props ->
    RText {
        +props
        role = TextRole.Body
    }
}

val RLabel = FC<RTextProps> { props ->
    RText {
        +props
        role = TextRole.Label
    }
}

val RCaption = FC<RTextProps> { props ->
    RText {
        +props
        role = TextRole.Caption
        size = TextSize.Small
    }
}
