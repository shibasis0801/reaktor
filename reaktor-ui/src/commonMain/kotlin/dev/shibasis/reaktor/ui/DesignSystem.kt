package dev.shibasis.reaktor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.js.JsExport

interface DesignSystem {
    val typography: Typography
        @Composable get() = MaterialTheme.typography

    val colorScheme: ColorScheme
        @Composable get() = MaterialTheme.colorScheme

    val shapes: Shapes
        @Composable get() = MaterialTheme.shapes

    val spacing: Spacing
        get() = Spacing()

    @Composable
    fun ButtonPrimary(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier,
        enabled: Boolean,
        icon: @Composable (() -> Unit)?
    ) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            content = {
                if (icon != null) {
                    icon()
                }
                Text(text)
            }
        )
    }


    @Composable
    fun ButtonSecondary(
        text: String,
        onClick: () -> Unit,
        modifier: Modifier,
        enabled: Boolean,
        icon: @Composable (() -> Unit)?
    ) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            content = {
                if (icon != null) {
                    icon()
                }
                Text(text)
            }
        )
    }

    @Composable
    fun TextView(text: String, style: TextStyle, modifier: Modifier) {
        Text(text, style = style, modifier = modifier)
    }

    @Composable
    fun InputText(
        value: String,
        onValueChange: (String) -> Unit,
        label: @Composable (() -> Unit),
        placeholder: String,
        modifier: Modifier,
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
