package dev.shibasis.reaktor.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.ui.atoms.*
import dev.shibasis.reaktor.ui.core.adaptive.*
import dev.shibasis.reaktor.ui.core.components.ComponentSize
import dev.shibasis.reaktor.ui.core.components.ComponentVariant
import dev.shibasis.reaktor.ui.core.components.TextSpec
import dev.shibasis.reaktor.ui.core.theme.ReaktorTheme
import dev.shibasis.reaktor.ui.core.tokens.Tokens
import dev.shibasis.reaktor.ui.material.MaterialTokens
import dev.shibasis.reaktor.ui.molecules.*

/**
 * ReaktorUI Demo
 *
 * A comprehensive demo showcasing the entire UI system:
 * - Token-based theming
 * - Atomic components (Button, Text, Input, Card, etc.)
 * - Molecules (ListItem, SearchBar, EmptyState)
 * - Responsive/Adaptive layouts
 * - Theme switching
 */

@Composable
fun ReaktorUIDemo() {
    var darkMode by remember { mutableStateOf(false) }
    var selectedTheme by remember { mutableStateOf(ThemeOption.Reaktor) }

    val tokens = when (selectedTheme) {
        ThemeOption.Default -> if (darkMode) MaterialTokens.dark(
            primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
            secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
        ) else MaterialTokens.light(
            primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
            secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
        )
        ThemeOption.Blue -> if (darkMode) MaterialTokens.dark(
            primary = androidx.compose.ui.graphics.Color(0xFF1976D2),
            secondary = androidx.compose.ui.graphics.Color(0xFF455A64),
        ) else MaterialTokens.light(
            primary = androidx.compose.ui.graphics.Color(0xFF1976D2),
            secondary = androidx.compose.ui.graphics.Color(0xFF455A64),
        )
        ThemeOption.Green -> if (darkMode) MaterialTokens.dark(
            primary = androidx.compose.ui.graphics.Color(0xFF388E3C),
            secondary = androidx.compose.ui.graphics.Color(0xFF5D4037),
        ) else MaterialTokens.light(
            primary = androidx.compose.ui.graphics.Color(0xFF388E3C),
            secondary = androidx.compose.ui.graphics.Color(0xFF5D4037),
        )
        ThemeOption.Reaktor -> if (darkMode) MaterialTokens.dark(
            primary = androidx.compose.ui.graphics.Color(0xFF702632),
            secondary = androidx.compose.ui.graphics.Color(0xFF008080),
        ) else MaterialTokens.light(
            primary = androidx.compose.ui.graphics.Color(0xFF702632),
            secondary = androidx.compose.ui.graphics.Color(0xFF008080),
            background = androidx.compose.ui.graphics.Color(0xFFE0F7FA),
        )
    }

    ReaktorTheme(tokens = tokens) {
        ResponsiveLayout(
            modifier = Modifier
                .fillMaxSize()
                .background(Tokens.colors.background)
        ) { windowSize ->
            DemoContent(
                windowSize = windowSize,
                darkMode = darkMode,
                onDarkModeChange = { darkMode = it },
                selectedTheme = selectedTheme,
                onThemeChange = { selectedTheme = it },
            )
        }
    }
}

enum class ThemeOption { Default, Blue, Green, Reaktor }

@Composable
private fun DemoContent(
    windowSize: WindowSize,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    selectedTheme: ThemeOption,
    onThemeChange: (ThemeOption) -> Unit,
) {
    val spacing = Tokens.spacing
    val colors = Tokens.colors

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                horizontal = if (windowSize.isMobile) spacing.md else spacing.xl,
                vertical = spacing.lg
            )
    ) {
        // Header
        DemoHeader(windowSize)

        RVerticalSpaceLg()

        // Theme Controls
        ThemeControlsSection(
            darkMode = darkMode,
            onDarkModeChange = onDarkModeChange,
            selectedTheme = selectedTheme,
            onThemeChange = onThemeChange,
        )

        RVerticalSpaceXl()

        // Responsive grid for sections
        val columns = responsiveColumns(mobile = 1, tablet = 2, desktop = 2)

        if (columns == 1) {
            // Mobile: Single column
            ButtonsSection()
            RVerticalSpaceLg()
            TextSection()
            RVerticalSpaceLg()
            InputSection()
            RVerticalSpaceLg()
            CardsSection()
            RVerticalSpaceLg()
            ListSection()
            RVerticalSpaceLg()
            ProgressSection()
            RVerticalSpaceLg()
            ChipsSection()
        } else {
            // Tablet/Desktop: Two columns
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ButtonsSection()
                    RVerticalSpaceLg()
                    InputSection()
                    RVerticalSpaceLg()
                    ListSection()
                    RVerticalSpaceLg()
                    ChipsSection()
                }
                Column(modifier = Modifier.weight(1f)) {
                    TextSection()
                    RVerticalSpaceLg()
                    CardsSection()
                    RVerticalSpaceLg()
                    ProgressSection()
                    RVerticalSpaceLg()
                    TogglesSection()
                }
            }
        }

        RVerticalSpaceXl()

        // Footer info
        FooterInfo(windowSize)
    }
}

@Composable
private fun DemoHeader(windowSize: WindowSize) {
    val colors = Tokens.colors

    Column {
        RDisplayText(
            text = "Reaktor UI",
            size = if (windowSize.isMobile) TextSpec.TextSize.Small else TextSpec.TextSize.Medium,
        )
        RVerticalSpaceXs()
        RBody(
            text = "Cross-platform UI components for Compose & React",
            color = colors.onSurfaceVariant,
        )
        RVerticalSpaceSm()
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)
        ) {
            RBadgeStandalone(
                text = windowSize.screenClass.name,
                color = dev.shibasis.reaktor.ui.core.components.BadgeSpec.BadgeColor.Primary,
            )
            RBadgeStandalone(
                text = "${windowSize.width.value.toInt()}dp",
                color = dev.shibasis.reaktor.ui.core.components.BadgeSpec.BadgeColor.Secondary,
            )
        }
    }
}

@Composable
private fun ThemeControlsSection(
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    selectedTheme: ThemeOption,
    onThemeChange: (ThemeOption) -> Unit,
) {
    RElevatedCard {
        Column(modifier = Modifier.padding(Tokens.spacing.md)) {
            RTitle(text = "Theme Controls")
            RVerticalSpaceMd()

            RListItemSwitch(
                headlineText = "Dark Mode",
                supportingText = if (darkMode) "Dark theme active" else "Light theme active",
                checked = darkMode,
                onCheckedChange = onDarkModeChange,
            )

            RHorizontalDivider()
            RVerticalSpaceSm()

            RLabel(text = "Color Theme")
            RVerticalSpaceXs()
            Row(
                horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)
            ) {
                ThemeOption.entries.forEach { theme ->
                    RFilterChip(
                        label = theme.name,
                        selected = selectedTheme == theme,
                        onClick = { onThemeChange(theme) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ROutlinedCard {
        Column(modifier = Modifier.padding(Tokens.spacing.md)) {
            RTitle(text = title, size = TextSpec.TextSize.Medium)
            RVerticalSpaceMd()
            content()
        }
    }
}

@Composable
private fun ButtonsSection() {
    SectionCard(title = "Buttons") {
        RLabel(text = "Variants")
        RVerticalSpaceXs()
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            RButtonPrimary(onClick = {}, label = "Primary")
            RButtonSecondary(onClick = {}, label = "Secondary")
        }
        RVerticalSpaceSm()
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            RButtonOutlined(onClick = {}, label = "Outlined")
            RButtonText(onClick = {}, label = "Text")
        }

        RVerticalSpaceMd()
        RLabel(text = "With Icons")
        RVerticalSpaceXs()
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            RButton(
                onClick = {},
                label = "Add Item",
                icon = Icons.Default.Add,
            )
            RIconButton(
                onClick = {},
                icon = Icons.Default.Favorite,
                variant = ComponentVariant.Tonal,
            )
        }

        RVerticalSpaceMd()
        RLabel(text = "Sizes")
        RVerticalSpaceXs()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        ) {
            RButtonPrimary(onClick = {}, label = "Small", size = ComponentSize.Small)
            RButtonPrimary(onClick = {}, label = "Medium", size = ComponentSize.Medium)
            RButtonPrimary(onClick = {}, label = "Large", size = ComponentSize.Large)
        }

        RVerticalSpaceMd()
        RFloatingActionButton(
            onClick = {},
            icon = Icons.Default.Edit,
            label = "Compose",
        )
    }
}

@Composable
private fun TextSection() {
    SectionCard(title = "Typography") {
        RDisplayText(text = "Display", size = TextSpec.TextSize.Small)
        RHeadline(text = "Headline")
        RTitle(text = "Title")
        RBody(text = "Body text for main content. This demonstrates the body text style which is used for paragraphs and general content.")
        RLabel(text = "Label")
        RCaption(text = "Caption text for metadata")
    }
}

@Composable
private fun InputSection() {
    var text by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }

    SectionCard(title = "Inputs") {
        RInput(
            value = text,
            onValueChange = { text = it },
            label = "Text Input",
            placeholder = "Enter some text...",
            modifier = Modifier.fillMaxWidth(),
        )

        RVerticalSpaceMd()

        REmailInput(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            modifier = Modifier.fillMaxWidth(),
        )

        RVerticalSpaceMd()

        RSearchBar(
            query = search,
            onQueryChange = { search = it },
            placeholder = "Search...",
        )
    }
}

@Composable
private fun CardsSection() {
    SectionCard(title = "Cards") {
        RLabel(text = "Elevated")
        RVerticalSpaceXs()
        RElevatedCard {
            Column(modifier = Modifier.padding(Tokens.spacing.md)) {
                RTitle(text = "Elevated Card", size = TextSpec.TextSize.Small)
                RBody(text = "With shadow elevation", size = TextSpec.TextSize.Small)
            }
        }

        RVerticalSpaceMd()
        RLabel(text = "Outlined")
        RVerticalSpaceXs()
        ROutlinedCard {
            Column(modifier = Modifier.padding(Tokens.spacing.md)) {
                RTitle(text = "Outlined Card", size = TextSpec.TextSize.Small)
                RBody(text = "With border", size = TextSpec.TextSize.Small)
            }
        }

        RVerticalSpaceMd()
        RLabel(text = "Filled")
        RVerticalSpaceXs()
        RFilledCard {
            Column(modifier = Modifier.padding(Tokens.spacing.md)) {
                RTitle(text = "Filled Card", size = TextSpec.TextSize.Small)
                RBody(text = "With container color", size = TextSpec.TextSize.Small)
            }
        }
    }
}

@Composable
private fun ListSection() {
    SectionCard(title = "List Items") {
        RListItemIcon(
            headlineText = "Settings",
            supportingText = "App configuration",
            icon = Icons.Default.Settings,
        )
        RHorizontalDivider()
        RListItemAvatar(
            headlineText = "John Doe",
            supportingText = "john@example.com",
            initials = "JD",
        )
        RHorizontalDivider()
        RListItemIcon(
            headlineText = "Notifications",
            supportingText = "Manage alerts",
            icon = Icons.Default.Notifications,
        )
    }
}

@Composable
private fun ProgressSection() {
    SectionCard(title = "Progress") {
        RLabel(text = "Circular (Indeterminate)")
        RVerticalSpaceXs()
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RCircularProgress(size = ComponentSize.Small)
            RCircularProgress(size = ComponentSize.Medium)
            RCircularProgress(size = ComponentSize.Large)
        }

        RVerticalSpaceMd()
        RLabel(text = "Circular (Determinate)")
        RVerticalSpaceXs()
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RCircularProgress(progress = 0.25f, size = ComponentSize.Medium)
            RCircularProgress(progress = 0.50f, size = ComponentSize.Medium)
            RCircularProgress(progress = 0.75f, size = ComponentSize.Medium)
        }

        RVerticalSpaceMd()
        RLabel(text = "Linear")
        RVerticalSpaceXs()
        RLinearProgress(modifier = Modifier.fillMaxWidth())
        RVerticalSpaceSm()
        RLinearProgress(progress = 0.6f, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TogglesSection() {
    var switch1 by remember { mutableStateOf(true) }
    var switch2 by remember { mutableStateOf(false) }
    var check1 by remember { mutableStateOf(true) }
    var check2 by remember { mutableStateOf(false) }
    var radio by remember { mutableStateOf(0) }

    SectionCard(title = "Toggles") {
        RLabel(text = "Switches")
        RVerticalSpaceXs()
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.md)) {
            RSwitch(checked = switch1, onCheckedChange = { switch1 = it })
            RSwitch(checked = switch2, onCheckedChange = { switch2 = it })
            RSwitch(checked = false, onCheckedChange = {}, enabled = false)
        }

        RVerticalSpaceMd()
        RLabel(text = "Checkboxes")
        RVerticalSpaceXs()
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.md)) {
            RCheckbox(checked = check1, onCheckedChange = { check1 = it })
            RCheckbox(checked = check2, onCheckedChange = { check2 = it })
            RCheckbox(checked = false, onCheckedChange = {}, enabled = false)
        }

        RVerticalSpaceMd()
        RLabel(text = "Radio Buttons")
        RVerticalSpaceXs()
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.md)) {
            RRadioButton(selected = radio == 0, onClick = { radio = 0 })
            RRadioButton(selected = radio == 1, onClick = { radio = 1 })
            RRadioButton(selected = radio == 2, onClick = { radio = 2 })
        }
    }
}

@Composable
private fun ChipsSection() {
    var filter1 by remember { mutableStateOf(true) }
    var filter2 by remember { mutableStateOf(false) }

    SectionCard(title = "Chips") {
        RLabel(text = "Assist Chips")
        RVerticalSpaceXs()
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
            RAssistChip(label = "Help", onClick = {}, leadingIcon = Icons.Default.Help)
            RAssistChip(label = "Calendar", onClick = {}, leadingIcon = Icons.Default.CalendarToday)
        }

        RVerticalSpaceMd()
        RLabel(text = "Filter Chips")
        RVerticalSpaceXs()
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
            RFilterChip(label = "Active", selected = filter1, onClick = { filter1 = !filter1 })
            RFilterChip(label = "Completed", selected = filter2, onClick = { filter2 = !filter2 })
        }

        RVerticalSpaceMd()
        RLabel(text = "Suggestion Chips")
        RVerticalSpaceXs()
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
            RSuggestionChip(label = "React", onClick = {})
            RSuggestionChip(label = "Kotlin", onClick = {})
            RSuggestionChip(label = "Compose", onClick = {})
        }
    }
}

@Composable
private fun FooterInfo(windowSize: WindowSize) {
    val colors = Tokens.colors

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RHorizontalDivider()
        RVerticalSpaceMd()
        RCaption(
            text = "Reaktor UI Demo",
            color = colors.onSurfaceVariant,
        )
        RCaption(
            text = "Screen: ${windowSize.screenClass} (${windowSize.width.value.toInt()} x ${windowSize.height.value.toInt()})",
            color = colors.onSurfaceVariant,
        )
    }
}
