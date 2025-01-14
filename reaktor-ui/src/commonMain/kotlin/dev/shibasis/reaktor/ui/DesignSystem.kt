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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/*
Figure out
1. Set Defaults
2. Better DX
3. Implement neo-morphism
*/
interface DesignSystem {
    @Composable fun getTypography() = MaterialTheme.typography

    @Composable fun getColorScheme(): ColorScheme = MaterialTheme.colorScheme

    @Composable fun getShapes(): Shapes = MaterialTheme.shapes

    fun getSizes() = Sizes()

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
    fun ButtonFloatingAction(
        modifier: Modifier,
        onClick: () -> Unit,
        content: @Composable RowScope.() -> Unit
    ) {
        ExtendedFloatingActionButton(
            modifier = modifier,
            onClick = onClick,
            content = content
        )
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


class Theme(private val designSystem: DesignSystem) {
    val colors: ColorScheme @Composable get() = designSystem.getColorScheme()
    val text: Typography @Composable get() = designSystem.getTypography()
    val shapes: Shapes @Composable get() = designSystem.getShapes()
    val sizes = designSystem.getSizes()

    @Composable
    fun ButtonPrimary(
        modifier: Modifier = Modifier,
        onClick: () -> Unit = {},
        content: @Composable RowScope.() -> Unit = {}
    ) {
        designSystem.ButtonPrimary(modifier, onClick, content)
    }

    @Composable
    fun ButtonSecondary(
        modifier: Modifier = Modifier,
        onClick: () -> Unit = {},
        content: @Composable RowScope.() -> Unit = {}
    ) {
        designSystem.ButtonSecondary(modifier, onClick, content)
    }

    @Composable
    fun ButtonIcon(
        modifier: Modifier = Modifier,
        imageVector: ImageVector,
        onClick: () -> Unit = {}
    ) {
        designSystem.ButtonIcon(modifier, imageVector, onClick)
    }


    @Composable
    fun ButtonFloatingAction(
        modifier: Modifier = Modifier,
        onClick: () -> Unit = {},
        content: @Composable RowScope.() -> Unit = {}
    ) {
        designSystem.ButtonFloatingAction(modifier, onClick, content)
    }

    @Composable
    fun TextView(
        modifier: Modifier = Modifier,
        text: String = "",
        style: TextStyle = this.text.bodyMedium
    ) {
        designSystem.TextView(modifier, text, style)
    }

    @Composable
    fun CardView(
        modifier: Modifier = Modifier,
        content: @Composable BoxScope.() -> Unit = {}
    ) {
        designSystem.CardView(modifier, content)
    }

    @Composable
    fun InputText(
        modifier: Modifier = Modifier,
        value: String = "",
        onValueChange: (String) -> Unit = {},
        label: @Composable (() -> Unit) = {},
        placeholder: String = "",
    ) {
        designSystem.InputText(modifier, value, onValueChange, label, placeholder)
    }

    fun Modifier.paddingExtraSmall() = padding(sizes.extraSmall)
    fun Modifier.paddingSmall() = padding(sizes.small)
    fun Modifier.paddingMedium() = padding(sizes.medium)
    fun Modifier.paddingLarge() = padding(sizes.large)
    fun Modifier.paddingExtraLarge() = padding(sizes.extraLarge)
}

var Feature.Theme by CreateSlot<Theme>()

data class Sizes(
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp
)


val Color.Companion.Teal: Color
    get() = Color(0, 128, 128)