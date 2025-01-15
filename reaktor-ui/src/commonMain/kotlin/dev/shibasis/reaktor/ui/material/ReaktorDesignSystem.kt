package dev.shibasis.reaktor.ui.material

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.ui.DesignSystem
import dev.shibasis.reaktor.ui.theme.darkScheme
import dev.shibasis.reaktor.ui.theme.lightScheme

internal object ReaktorColor {
    val SkyBlue = Color(0xFFE0F7FA)
    val Wine = Color(0xFF702632)
    val Teal = Color(0xFF008080)
    val Black = Color(0xFF282c3f)
}

object ReaktorDesignSystem: DesignSystem {

    @Composable override fun getTypography() = MaterialTheme.typography
    @Composable override fun getColorScheme() = lightScheme
}