package dev.shibasis.reaktor.ui.molecules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import dev.shibasis.reaktor.ui.atoms.RIconButton
import dev.shibasis.reaktor.ui.atoms.RInput
import dev.shibasis.reaktor.ui.core.components.ComponentSize
import dev.shibasis.reaktor.ui.core.components.ComponentVariant
import dev.shibasis.reaktor.ui.core.components.InputSpec
import dev.shibasis.reaktor.ui.core.tokens.Tokens

/**
 * Molecule: SearchBar
 *
 * A search input with leading search icon and optional clear button.
 * Commonly used in app bars and search screens.
 */

@Composable
fun RSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search...",
    onSearch: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = Tokens.colors
    val shapes = Tokens.shapes
    val spacing = Tokens.spacing
    val sizing = Tokens.sizing

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(shapes.full),
        color = colors.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading icon
            Box(
                modifier = Modifier.size(sizing.touchTargetMin),
                contentAlignment = Alignment.Center,
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                } else {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = colors.onSurfaceVariant,
                    )
                }
            }

            // Input field
            TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                singleLine = true,
                enabled = enabled,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceContainerHigh,
                    unfocusedContainerColor = colors.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onSearch?.invoke() },
                ),
            )

            // Clear/trailing icon
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = {
                        onQueryChange("")
                        onClear?.invoke()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = colors.onSurfaceVariant,
                    )
                }
            } else if (trailingIcon != null) {
                Box(
                    modifier = Modifier.size(sizing.touchTargetMin),
                    contentAlignment = Alignment.Center,
                ) {
                    trailingIcon()
                }
            }
        }
    }
}

/**
 * Expanded search bar for search screens
 */
@Composable
fun RSearchBarExpanded(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search...",
    onSearch: (() -> Unit)? = null,
    autoFocus: Boolean = true,
) {
    val colors = Tokens.colors
    val spacing = Tokens.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RIconButton(
            onClick = onBack,
            icon = Icons.Default.Clear,
            variant = ComponentVariant.Text,
        )

        RSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = placeholder,
            onSearch = onSearch,
        )
    }
}
