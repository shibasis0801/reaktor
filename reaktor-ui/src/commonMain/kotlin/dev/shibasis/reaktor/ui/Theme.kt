package dev.shibasis.reaktor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingActionButtonElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
4. Minimal system as much as possible including function overhead
*/
@Suppress("ABSTRACT_COMPOSABLE_DEFAULT_PARAMETER_VALUE")
open class Theme {
    open val colors: ColorScheme @Composable get() = MaterialTheme.colorScheme
    open val text: Typography @Composable get() = MaterialTheme.typography
    open val shapes: Shapes @Composable get() = MaterialTheme.shapes
    open val sizes: Sizes get() = Sizes()

    fun Modifier.paddingExtraSmall() = padding(sizes.extraSmall)
    fun Modifier.paddingSmall() = padding(sizes.small)
    fun Modifier.paddingMedium() = padding(sizes.medium)
    fun Modifier.paddingLarge() = padding(sizes.large)
    fun Modifier.paddingExtraLarge() = padding(sizes.extraLarge)

    @Composable
    open fun ButtonWrapped(
        modifier: Modifier,
        onClick: () -> Unit,
        enabled: Boolean,
        shape: Shape,
        colors: ButtonColors,
        elevation: ButtonElevation?,
        contentPadding: PaddingValues,
        content: @Composable RowScope.() -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = colors,
            elevation = elevation,
            content = content,
            contentPadding = contentPadding
        )
    }

    @Composable
    open fun ButtonPrimary(
        modifier: Modifier = Modifier,
        onClick: () -> Unit = {},
        enabled: Boolean = true,
        shape: Shape = ButtonDefaults.shape,
        colors: ButtonColors = ButtonDefaults.buttonColors(),
        elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
        contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
        content: @Composable RowScope.() -> Unit = {}
    ) {
        ButtonWrapped(modifier, onClick, enabled, shape, colors, elevation, contentPadding, content)
    }

    @Composable
    open fun ButtonSecondary(
        modifier: Modifier = Modifier,
        onClick: () -> Unit = {},
        enabled: Boolean = true,
        shape: Shape = ButtonDefaults.shape,
        colors: ButtonColors = ButtonDefaults.buttonColors(), // change default colors for secondary
        elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
        contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
        content: @Composable RowScope.() -> Unit = {}
    ) {
        ButtonWrapped(modifier, onClick, enabled, shape, colors, elevation, contentPadding, content)
    }

    @Composable
    open fun ButtonIcon(
        modifier: Modifier = Modifier,
        imageVector: ImageVector,
        onClick: () -> Unit = {}
    ) {
        Box(modifier.clickable(onClick = onClick)) {
            Icon(imageVector, imageVector.name)
        }
    }


    @Composable
    open fun ButtonFloatingAction(
        modifier: Modifier = Modifier,
        onClick: () -> Unit = {},
        shape: Shape = FloatingActionButtonDefaults.extendedFabShape,
        containerColor: Color = FloatingActionButtonDefaults.containerColor,
        contentColor: Color = contentColorFor(containerColor),
        elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
        content: @Composable RowScope.() -> Unit = {}
    ) {
        ExtendedFloatingActionButton(
            modifier = modifier,
            onClick = onClick,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            elevation = elevation,
            content = content
        )
    }

    @Composable
    open fun TextView(
        modifier: Modifier = Modifier,
        text: String = "",
        style: TextStyle = this.text.bodyMedium
    ) {
        Text(text, style = style, modifier = modifier)
    }

    @Composable
    open fun CardView(
        modifier: Modifier = Modifier,
        content: @Composable BoxScope.() -> Unit = {}
    ) {
        Box(modifier = modifier.border(0.25.dp, Color.LightGray, shape = RoundedCornerShape(8.dp)).padding(8.dp), content = content)
    }

    @Composable
    open fun InputText(
        modifier: Modifier = Modifier,
        value: String = "",
        onValueChange: (String) -> Unit = {},
        label: @Composable (() -> Unit) = {},
        placeholder: String = "",
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
    open fun Space(height: Dp = 0.dp, width: Dp = 0.dp) {
        Spacer(Modifier.width(width).height(height))
    }

}

var Feature.Theme by CreateSlot<Theme>()

@Composable
inline fun themed(block: Theme.() -> Unit) {
    block(Feature.Theme ?: Theme())
}

data class Sizes(
    val extraSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val extraLarge: Dp = 32.dp
)


val Color.Companion.Teal: Color
    get() = Color(0, 128, 128)

val Color.Companion.BlackText: Color
    get() = Color(0xFF282c3f)
