package dev.shibasis.reaktor.ui.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.ui.DesignSystem

object ReaktorDesignSystem: DesignSystem {
    override val typography: Typography
        @Composable get() = MaterialTheme.typography
}