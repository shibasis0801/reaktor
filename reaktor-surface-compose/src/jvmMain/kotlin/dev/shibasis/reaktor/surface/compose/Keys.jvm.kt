package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.input.key.Key
import dev.shibasis.reaktor.surface.KeyConvention

actual fun platformKeyConvention(): KeyConvention =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) KeyConvention.Mac else KeyConvention.Pc

internal actual val ContextMenuKey: Key = Key(java.awt.event.KeyEvent.VK_CONTEXT_MENU)
