package dev.shibasis.reaktor.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZoneId

/**
 * [WidgetBoard] on Android: the snapshot in a SharedPreferences file, and every placed widget of
 * [widgets] redrawn from it.
 *
 * SharedPreferences, not the app's database: the widget host can start the app's receiver on its
 * own, with no graph, no adapters and no SQLite handle, and a small file read straight off disk is
 * what works there.
 *
 * Build it from the Application (not an Activity): an app publishes when its data changes, which
 * may be with no screen open.
 */
class AndroidWidgets(context: Context, private val widgets: List<Class<out SnapshotWidget>>) : WidgetBoard {
    private val app = context.applicationContext

    override fun publish(snapshot: String, key: String) {
        runCatching {
            store(app).edit().putString(key, snapshot).apply()
            for (widget in widgets) SnapshotWidget.redraw(app, widget)
        }
    }

    override fun read(key: String): String? = runCatching { store(app).getString(key, null) }.getOrNull()

    internal companion object {
        const val STORE = "reaktor_widget"

        fun store(context: Context) = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    }
}

/**
 * One kind of home-screen widget: a receiver that draws the snapshot the app published, and
 * computes nothing itself.
 *
 * Subclass it, draw in [render], and declare the subclass in the app's manifest:
 *
 * ```xml
 * <receiver android:name=".SpendWidget" android:exported="false" android:label="@string/spend_widget">
 *     <intent-filter>
 *         <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
 *         <!-- Redraw when the clock or the time zone moves; both may be sent to a manifest receiver. -->
 *         <action android:name="android.intent.action.TIME_SET" />
 *         <action android:name="android.intent.action.TIMEZONE_CHANGED" />
 *     </intent-filter>
 *     <meta-data android:name="android.appwidget.provider" android:resource="@xml/spend_widget" />
 * </receiver>
 * ```
 *
 * The provider XML sets `updatePeriodMillis="0"`: the system never redraws on a timer. This class
 * redraws when the app publishes, when the system asks (a widget placed, the phone restarted,
 * the app updated), when the clock moves, and once a day at midnight, so a snapshot that says
 * "today" can tell when it no longer is.
 */
abstract class SnapshotWidget : AppWidgetProvider() {
    /** The views for [snapshot], or for nothing published yet (null). */
    protected abstract fun render(context: Context, snapshot: String?): RemoteViews

    /** Which snapshot this widget draws. */
    protected open val key: String = WidgetBoard.DEFAULT_KEY

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = draw(context)
        for (id in appWidgetIds) manager.updateAppWidget(id, views)
        midnight(context, javaClass)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> redraw(context, javaClass)
        }
    }

    override fun onDisabled(context: Context) {
        // The last one is gone: nothing left for the midnight redraw to draw.
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(tick(context, javaClass)) }
    }

    internal fun draw(context: Context): RemoteViews =
        render(context, runCatching { AndroidWidgets.store(context).getString(key, null) }.getOrNull())

    /**
     * Opens the app at [url] ("coffer://cash"): its launcher Activity, with the link as the
     * intent's data, for it to hand to `Graph.open(DeepLink.parse(url))`. Each link is its own
     * PendingIntent; the data keeps two buttons from replacing each other.
     */
    protected fun open(context: Context, url: String? = null): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        if (url != null) launch.data = Uri.parse(url)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, url.hashCode(), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val ACTION_TICK = "dev.shibasis.reaktor.widget.TICK"

        /** Redraws every placed widget of [widget]. Cheap when none is placed. */
        fun redraw(context: Context, widget: Class<out SnapshotWidget>) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, widget)
            if (manager.getAppWidgetIds(component).isEmpty()) return
            val instance = runCatching { widget.getDeclaredConstructor().newInstance() }.getOrNull() ?: return
            manager.updateAppWidget(component, instance.draw(context))
            midnight(context, widget)
        }

        /**
         * The next midnight, inexact: it lands while the phone's owner is asleep, an hour of drift
         * costs nothing, and an inexact alarm needs no permission.
         */
        private fun midnight(context: Context, widget: Class<out SnapshotWidget>) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            runCatching { alarms.set(AlarmManager.RTC, at, tick(context, widget)) }
        }

        private fun tick(context: Context, widget: Class<out SnapshotWidget>): PendingIntent =
            PendingIntent.getBroadcast(
                context, widget.name.hashCode(), Intent(context, widget).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
