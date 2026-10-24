package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ScreenClass { Phone, Tablet, Desktop }

@Immutable
data class ScreenBreakpoints(val tablet: Dp = 600.dp, val desktop: Dp = 840.dp) {
    fun classOf(width: Dp): ScreenClass = when {
        width >= desktop -> ScreenClass.Desktop
        width >= tablet -> ScreenClass.Tablet
        else -> ScreenClass.Phone
    }
}

val LocalScreenBreakpoints = staticCompositionLocalOf { ScreenBreakpoints() }

val LocalScreenWidth = compositionLocalOf<Dp?> { null }

@Composable
fun screenWidth(): Dp = LocalScreenWidth.current ?: with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

@Composable
fun screenClass(): ScreenClass = LocalScreenBreakpoints.current.classOf(screenWidth())
