package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.input.key.Key
import dev.shibasis.reaktor.surface.KeyConvention
import platform.UIKit.UIKeyboardHIDUsageKeyboardApplication

actual fun platformKeyConvention(): KeyConvention = KeyConvention.Mac

internal actual val ContextMenuKey: Key = Key(UIKeyboardHIDUsageKeyboardApplication)
