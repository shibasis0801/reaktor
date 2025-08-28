package dev.shibasis.reaktor.auth.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathFillType.Companion.NonZero
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.ui.Theme

object AuthButtonSpec {
    fun textStyle(typography: Typography) = typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
}

@Composable
fun Theme.GoogleLoginButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: String = "Continue with Google"
) {
    val darkTheme = isSystemInDarkTheme()

    val googleButtonColors = if (darkTheme) {
        ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1F1F1F),
            contentColor = Color.White
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color(0xFF3C4043)
        )
    }

    ButtonPrimary(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = shapes.medium,
        colors = googleButtonColors,
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            modifier = Modifier.size(14.dp),
            imageVector = Icons.Google,
            contentDescription = "Google Login",
            tint = Color.Unspecified
        )
        Spacer(Modifier.size(sizes.small))
        TextView(text = content, style = text.bodyLarge.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
fun Theme.AppleLoginButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: String = "Continue with Apple"
) {
    val colors = if (isSystemInDarkTheme())
        ButtonDefaults.buttonColors(
        containerColor = Color.White,
        contentColor = Color.Black
    ) else ButtonDefaults.buttonColors(
        containerColor = Color.Black,
        contentColor = Color.White
    )

    ButtonPrimary(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(48.dp),
        shape = shapes.medium,
        contentPadding = PaddingValues(0.dp),
        colors = colors
    ) {
        Icon(
            modifier = Modifier.size(14.dp),
            imageVector = Icons.Apple,
            contentDescription = "Apple Login"
        )
        Spacer(Modifier.size(sizes.small))
        TextView(text = content, style = text.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

