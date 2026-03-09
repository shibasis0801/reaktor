package dev.shibasis.reaktor.ui.demo

import dev.shibasis.reaktor.ui.components.*
import dev.shibasis.reaktor.ui.tokens.*
import js.objects.jso
import react.*
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.hr
import react.dom.html.ReactHTML.section
import web.cssom.*
import kotlin.js.JsExport

/**
 * ReaktorUI Demo for React (Kotlin/JS)
 *
 * A comprehensive demo showcasing the entire UI system in React.
 * This component is exported to JavaScript and can be used directly in React apps.
 */

external interface ReaktorUIDemoProps : Props {
    var initialDarkMode: Boolean?
}

@JsExport
val ReaktorUIDemo = FC<ReaktorUIDemoProps> { props ->
    var darkMode by useState(props.initialDarkMode ?: false)
    var selectedTheme by useState(ThemeOption.Reaktor)

    val tokens = useMemo(darkMode, selectedTheme) {
        when (selectedTheme) {
            ThemeOption.Default -> if (darkMode)
                createWebTokens("#6750A4", "#625B71", "#7D5260", true)
            else
                createWebTokens("#6750A4", "#625B71", "#7D5260", false)

            ThemeOption.Blue -> if (darkMode)
                createWebTokens("#1976D2", "#455A64", "#00796B", true)
            else
                createWebTokens("#1976D2", "#455A64", "#00796B", false)

            ThemeOption.Green -> if (darkMode)
                createWebTokens("#388E3C", "#5D4037", "#00796B", true)
            else
                createWebTokens("#388E3C", "#5D4037", "#00796B", false)

            ThemeOption.Reaktor -> if (darkMode)
                WebMaterialTokens.reaktorDark
            else
                WebMaterialTokens.reaktorLight
        }
    }

    val colors = tokens.colors
    val spacing = tokens.spacing
    val shapes = tokens.shapes

    div {
        style = jso {
            minHeight = 100.vh
            backgroundColor = colors.background.unsafeCast<BackgroundColor>()
            color = colors.onBackground.unsafeCast<Color>()
            fontFamily = FontFamily.sansSerif
            padding = spacing.lg.unsafeCast<Padding>()
        }

        // Header
        div {
            style = jso {
                marginBottom = spacing.xl.unsafeCast<MarginBottom>()
            }

            RText {
                this.tokens = tokens
                text = "Reaktor UI"
                role = TextRole.Display
                size = TextSize.Medium
            }

            RText {
                this.tokens = tokens
                text = "Cross-platform UI components for Compose & React"
                role = TextRole.Body
                color = colors.onSurfaceVariant
            }
        }

        // Theme Controls
        RCard {
            this.tokens = tokens
            variant = ComponentVariant.Elevated
            padding = spacing.md

            div {
                RText {
                    this.tokens = tokens
                    text = "Theme Controls"
                    role = TextRole.Title
                }

                div {
                    style = jso {
                        display = Display.flex
                        alignItems = AlignItems.center
                        gap = spacing.md.unsafeCast<Gap>()
                        marginTop = spacing.md.unsafeCast<MarginTop>()
                        marginBottom = spacing.md.unsafeCast<MarginBottom>()
                    }

                    RText {
                        this.tokens = tokens
                        text = "Dark Mode:"
                        role = TextRole.Body
                    }

                    RButton {
                        this.tokens = tokens
                        label = if (darkMode) "ON" else "OFF"
                        variant = if (darkMode) ComponentVariant.Filled else ComponentVariant.Outlined
                        onClick = { darkMode = !darkMode }
                    }
                }

                div {
                    style = jso {
                        display = Display.flex
                        gap = spacing.sm.unsafeCast<Gap>()
                        flexWrap = FlexWrap.wrap
                    }

                    ThemeOption.entries.forEach { theme ->
                        RButton {
                            this.tokens = tokens
                            label = theme.name
                            variant = if (selectedTheme == theme) ComponentVariant.Filled else ComponentVariant.Outlined
                            size = ComponentSize.Small
                            onClick = { selectedTheme = theme }
                        }
                    }
                }
            }
        }

        div {
            style = jso {
                height = spacing.xl.unsafeCast<Height>()
            }
        }

        // Two column layout for larger screens
        div {
            style = jso {
                display = Display.grid
                gridTemplateColumns = "repeat(auto-fit, minmax(320px, 1fr))".unsafeCast<GridTemplateColumns>()
                gap = spacing.lg.unsafeCast<Gap>()
            }

            // Buttons Section
            DemoSection {
                this.tokens = tokens
                title = "Buttons"

                div {
                    style = jso {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = spacing.md.unsafeCast<Gap>()
                    }

                    RText {
                        this.tokens = tokens
                        text = "Variants"
                        role = TextRole.Label
                    }

                    div {
                        style = jso {
                            display = Display.flex
                            gap = spacing.sm.unsafeCast<Gap>()
                            flexWrap = FlexWrap.wrap
                        }

                        RButton {
                            this.tokens = tokens
                            label = "Primary"
                            variant = ComponentVariant.Filled
                            onClick = {}
                        }
                        RButton {
                            this.tokens = tokens
                            label = "Secondary"
                            variant = ComponentVariant.Tonal
                            onClick = {}
                        }
                        RButton {
                            this.tokens = tokens
                            label = "Outlined"
                            variant = ComponentVariant.Outlined
                            onClick = {}
                        }
                        RButton {
                            this.tokens = tokens
                            label = "Text"
                            variant = ComponentVariant.Text
                            onClick = {}
                        }
                    }

                    RText {
                        this.tokens = tokens
                        text = "Sizes"
                        role = TextRole.Label
                    }

                    div {
                        style = jso {
                            display = Display.flex
                            gap = spacing.sm.unsafeCast<Gap>()
                            alignItems = AlignItems.center
                        }

                        RButton {
                            this.tokens = tokens
                            label = "Small"
                            size = ComponentSize.Small
                            onClick = {}
                        }
                        RButton {
                            this.tokens = tokens
                            label = "Medium"
                            size = ComponentSize.Medium
                            onClick = {}
                        }
                        RButton {
                            this.tokens = tokens
                            label = "Large"
                            size = ComponentSize.Large
                            onClick = {}
                        }
                    }

                    RText {
                        this.tokens = tokens
                        text = "States"
                        role = TextRole.Label
                    }

                    div {
                        style = jso {
                            display = Display.flex
                            gap = spacing.sm.unsafeCast<Gap>()
                        }

                        RButton {
                            this.tokens = tokens
                            label = "Enabled"
                            state = ComponentState.Enabled
                            onClick = {}
                        }
                        RButton {
                            this.tokens = tokens
                            label = "Disabled"
                            state = ComponentState.Disabled
                            onClick = {}
                        }
                        RButton {
                            this.tokens = tokens
                            label = "Loading"
                            state = ComponentState.Loading
                            onClick = {}
                        }
                    }
                }
            }

            // Typography Section
            DemoSection {
                this.tokens = tokens
                title = "Typography"

                div {
                    style = jso {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = spacing.sm.unsafeCast<Gap>()
                    }

                    RText {
                        this.tokens = tokens
                        text = "Display"
                        role = TextRole.Display
                        size = TextSize.Small
                    }
                    RText {
                        this.tokens = tokens
                        text = "Headline"
                        role = TextRole.Headline
                        size = TextSize.Medium
                    }
                    RText {
                        this.tokens = tokens
                        text = "Title"
                        role = TextRole.Title
                        size = TextSize.Medium
                    }
                    RText {
                        this.tokens = tokens
                        text = "Body text for main content. This demonstrates the body style used for paragraphs."
                        role = TextRole.Body
                    }
                    RText {
                        this.tokens = tokens
                        text = "Label"
                        role = TextRole.Label
                    }
                    RText {
                        this.tokens = tokens
                        text = "Caption text for metadata"
                        role = TextRole.Caption
                    }
                }
            }

            // Cards Section
            DemoSection {
                this.tokens = tokens
                title = "Cards"

                div {
                    style = jso {
                        display = Display.flex
                        flexDirection = FlexDirection.column
                        gap = spacing.md.unsafeCast<Gap>()
                    }

                    RText {
                        this.tokens = tokens
                        text = "Elevated"
                        role = TextRole.Label
                    }
                    RCard {
                        this.tokens = tokens
                        variant = ComponentVariant.Elevated

                        RText {
                            this.tokens = tokens
                            text = "Elevated Card"
                            role = TextRole.Title
                            size = TextSize.Small
                        }
                        RText {
                            this.tokens = tokens
                            text = "With shadow elevation"
                            role = TextRole.Body
                            size = TextSize.Small
                        }
                    }

                    RText {
                        this.tokens = tokens
                        text = "Outlined"
                        role = TextRole.Label
                    }
                    RCard {
                        this.tokens = tokens
                        variant = ComponentVariant.Outlined

                        RText {
                            this.tokens = tokens
                            text = "Outlined Card"
                            role = TextRole.Title
                            size = TextSize.Small
                        }
                        RText {
                            this.tokens = tokens
                            text = "With border"
                            role = TextRole.Body
                            size = TextSize.Small
                        }
                    }

                    RText {
                        this.tokens = tokens
                        text = "Filled"
                        role = TextRole.Label
                    }
                    RCard {
                        this.tokens = tokens
                        variant = ComponentVariant.Filled

                        RText {
                            this.tokens = tokens
                            text = "Filled Card"
                            role = TextRole.Title
                            size = TextSize.Small
                        }
                        RText {
                            this.tokens = tokens
                            text = "With container color"
                            role = TextRole.Body
                            size = TextSize.Small
                        }
                    }
                }
            }

            // Color Palette Section
            DemoSection {
                this.tokens = tokens
                title = "Color Palette"

                div {
                    style = jso {
                        display = Display.grid
                        gridTemplateColumns = "repeat(4, 1fr)".unsafeCast<GridTemplateColumns>()
                        gap = spacing.xs.unsafeCast<Gap>()
                    }

                    ColorSwatch { this.tokens = tokens; color = colors.primary; name = "Primary" }
                    ColorSwatch { this.tokens = tokens; color = colors.secondary; name = "Secondary" }
                    ColorSwatch { this.tokens = tokens; color = colors.tertiary; name = "Tertiary" }
                    ColorSwatch { this.tokens = tokens; color = colors.error; name = "Error" }
                    ColorSwatch { this.tokens = tokens; color = colors.success; name = "Success" }
                    ColorSwatch { this.tokens = tokens; color = colors.warning; name = "Warning" }
                    ColorSwatch { this.tokens = tokens; color = colors.info; name = "Info" }
                    ColorSwatch { this.tokens = tokens; color = colors.surface; name = "Surface" }
                }
            }
        }

        // Footer
        div {
            style = jso {
                marginTop = spacing.xxl.unsafeCast<MarginTop>()
                textAlign = TextAlign.center
            }

            hr {
                style = jso {
                    border = None.none
                    borderTop = Border(1.px, LineStyle.solid, colors.outlineVariant.unsafeCast<Color>())
                    marginBottom = spacing.md.unsafeCast<MarginBottom>()
                }
            }

            RText {
                this.tokens = tokens
                text = "Reaktor UI Demo - Kotlin/JS React Components"
                role = TextRole.Caption
            }
        }
    }
}

// Helper Components

external interface DemoSectionProps : PropsWithChildren {
    var tokens: WebDesignTokens
    var title: String
}

val DemoSection = FC<DemoSectionProps> { props ->
    RCard {
        tokens = props.tokens
        variant = ComponentVariant.Outlined

        div {
            RText {
                tokens = props.tokens
                text = props.title
                role = TextRole.Title
                size = TextSize.Medium
            }

            div {
                style = jso {
                    marginTop = props.tokens.spacing.md.unsafeCast<MarginTop>()
                }
                props.children?.let { +it }
            }
        }
    }
}

external interface ColorSwatchProps : Props {
    var tokens: WebDesignTokens
    var color: String
    var name: String
}

val ColorSwatch = FC<ColorSwatchProps> { props ->
    val tokens = props.tokens
    val spacing = tokens.spacing
    val shapes = tokens.shapes

    div {
        style = jso {
            display = Display.flex
            flexDirection = FlexDirection.column
            alignItems = AlignItems.center
            gap = spacing.xxs.unsafeCast<Gap>()
        }

        div {
            style = jso {
                width = 40.px
                height = 40.px
                borderRadius = shapes.sm.unsafeCast<BorderRadius>()
                backgroundColor = props.color.unsafeCast<BackgroundColor>()
                border = Border(1.px, LineStyle.solid, tokens.colors.outlineVariant.unsafeCast<Color>())
            }
        }

        RText {
            this.tokens = tokens
            text = props.name
            role = TextRole.Caption
        }
    }
}
