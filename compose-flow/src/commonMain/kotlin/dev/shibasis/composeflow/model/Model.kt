package dev.shibasis.composeflow.model

import androidx.compose.runtime.Immutable

@Immutable
data class XYPosition(
    val x: Double = 0.0,
    val y: Double = 0.0,
)

@Immutable
data class Viewport(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val zoom: Double = 1.0,
)
