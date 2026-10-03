package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.input.key.Key
import dev.shibasis.reaktor.surface.KeyConvention

actual fun platformKeyConvention(): KeyConvention = KeyConvention.Pc

internal actual val ContextMenuKey: Key = Key.Menu
