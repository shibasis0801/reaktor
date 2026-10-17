package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAutomationScope: ProvidableCompositionLocal<String?> = staticCompositionLocalOf { null }

@Composable
fun AutomationScope(id: String, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalAutomationScope provides automationId(LocalAutomationScope.current, id), content = content)

internal fun automationId(scope: String?, part: String): String = if (scope == null) part else "$scope/$part"
