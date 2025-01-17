package dev.shibasis.reaktor.ui.material

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.ui.DesignSystem
import dev.shibasis.reaktor.ui.theme.darkScheme
import dev.shibasis.reaktor.ui.theme.lightScheme

internal object ReaktorColor {
    val SkyBlue = Color(0xFFE0F7FA)
    val Wine = Color(0xFF702632)
}

object ReaktorDesignSystem: DesignSystem {
    @Composable override fun getTypography() = MaterialTheme.typography
    @Composable override fun getColorScheme() = lightScheme
}
/*
Change to a class, pass dark/light as params.
Have default colors.
*/