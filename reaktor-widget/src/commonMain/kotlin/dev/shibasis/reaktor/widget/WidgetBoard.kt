package dev.shibasis.reaktor.widget

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

/**
 * Where an app leaves what its home-screen widgets show, and how it asks them to redraw.
 *
 * The snapshot is text the app chooses (JSON, usually), already worded and formatted in the
 * person's language: the widget draws strings and does no arithmetic, because it runs where the
 * app's code and data aren't. Keep it small; it is read on every redraw.
 *
 * A widget that has to show something different as days pass without the app running (yesterday's
 * "today", last month's total on the 1st) does that from what the snapshot says about when it was
 * made: put the date in it.
 */
interface WidgetBoard {
    /** Keeps [snapshot] under [key] and redraws the app's widgets. Never throws; a widget is a decoration. */
    fun publish(snapshot: String, key: String = DEFAULT_KEY)

    /** What was last published under [key], or null. */
    fun read(key: String = DEFAULT_KEY): String?

    companion object {
        const val DEFAULT_KEY = "snapshot"
    }
}

/** Empty where the platform has no home-screen widgets, or the app has none. */
var Feature.Widgets by CreateSlot<WidgetBoard>()
