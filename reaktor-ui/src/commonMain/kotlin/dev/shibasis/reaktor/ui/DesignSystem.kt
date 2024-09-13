package dev.shibasis.reaktor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


/*

Figure out
1. Set Defaults
2. Better DX
3. Implement neo-morphism

 */
interface DesignSystem {
    val text: Typography
        @Composable get() = MaterialTheme.typography

    val color: ColorScheme
        @Composable get() = MaterialTheme.colorScheme

    val shapes: Shapes
        @Composable get() = MaterialTheme.shapes

    val spacing: Spacing
        get() = Spacing()

    @Composable
    fun ButtonPrimary(
        modifier: Modifier,
        onClick: () -> Unit,
        content: @Composable RowScope.() -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            content = content
        )
    }

    @Composable
    fun ButtonSecondary(
        modifier: Modifier,
        onClick: () -> Unit,
        content: @Composable RowScope.() -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            content = content
        )
    }

    @Composable
    fun ButtonIcon(
        modifier: Modifier,
        imageVector: ImageVector,
        onClick: () -> Unit,
    ) {
        Box(modifier) {
            Icon(imageVector, imageVector.name)
        }
    }

    @Composable
    fun TextView(modifier: Modifier, text: String, style: TextStyle) {
        Text(text, style = style, modifier = modifier)
    }

    @Composable
    fun InputText(
        modifier: Modifier,
        value: String,
        onValueChange: (String) -> Unit,
        label: @Composable (() -> Unit),
        placeholder: String,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = { Text(placeholder) },
            modifier = modifier,
            leadingIcon = null,
            trailingIcon = null,
            enabled = true,
            singleLine = false,
            isError = false
        )
    }

    @Composable
    fun CardView(
        modifier: Modifier,
        content: @Composable BoxScope.() -> Unit
    ) {
        Box(modifier = modifier.border(0.25.dp, Color.LightGray, shape = RoundedCornerShape(8.dp)).padding(8.dp))
    }
}

val LocalDesignSystem = staticCompositionLocalOf<DesignSystem> {
    error("No DesignSystem provided")
}

val ui: DesignSystem
        @Composable
        get() = LocalDesignSystem.current

data class Spacing(
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp
)


val Color.Companion.Teal: Color
    get() = Color(0, 128, 128)