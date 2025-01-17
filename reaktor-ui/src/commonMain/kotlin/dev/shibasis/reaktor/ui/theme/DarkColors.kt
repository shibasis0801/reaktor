package dev.shibasis.reaktor.ui.theme

import androidx.compose.ui.graphics.Color

val darkScheme = minimalDarkColorScheme(
    primary = Color(0xFFBDC2FF),
    secondary = Color(0xFFC4C5DD),
    tertiary = Color(0xFFE7B9D6),
    surface = lighten(Color.DarkGray, -0.2f),
    background = Color(0xFF131318),
    error =  Color(0xFFFFB4AB)
)