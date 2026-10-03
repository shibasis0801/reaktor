package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.input.key.Key
import dev.shibasis.reaktor.surface.KeyConvention

actual fun platformKeyConvention(): KeyConvention =
    if (js("typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)") as Boolean) KeyConvention.Mac else KeyConvention.Pc

internal actual val ContextMenuKey: Key = Key.Menu
