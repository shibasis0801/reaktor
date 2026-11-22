package dev.shibasis.reaktor.widget

import platform.Foundation.NSUserDefaults

/**
 * [WidgetBoard] on iOS: the snapshot in [appGroup]'s UserDefaults, where the app's WidgetKit
 * extension reads it with `UserDefaults(suiteName:)` and the same key.
 *
 * The extension is a separate binary with its own sandbox; the App Group is the only ground the
 * two share, so both targets need the App Group capability with this name. Without it the suite is
 * nil on a device and this writes nowhere, which is the right failure for a decoration.
 *
 * [reload] is the app's to set from Swift, as `{ WidgetCenter.shared.reloadAllTimelines() }`:
 * WidgetKit has no Objective-C interface, so Kotlin can't call it. Left unset, the widget picks the
 * change up at its next timeline refresh.
 */
class DarwinWidgets(private val appGroup: String) : WidgetBoard {
    var reload: (() -> Unit)? = null

    override fun publish(snapshot: String, key: String) {
        runCatching {
            val defaults = NSUserDefaults(suiteName = appGroup) ?: return
            defaults.setObject(snapshot, forKey = key)
            reload?.invoke()
        }
    }

    override fun read(key: String): String? = runCatching { NSUserDefaults(suiteName = appGroup)?.stringForKey(key) }.getOrNull()
}
