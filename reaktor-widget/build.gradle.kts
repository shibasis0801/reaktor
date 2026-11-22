import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

// Home-screen widgets: the app publishes a snapshot, the widget draws it.
//
// A widget is drawn by something that isn't the app's process as it runs: Android's widget host
// calls a receiver that may start with no graph and no open database, and iOS runs WidgetKit in a
// separate extension with its own sandbox. So nothing here computes. The app writes a small
// snapshot (JSON) where the widget can read it without the app: a SharedPreferences file on
// Android, an App Group's UserDefaults on iOS. The widget lays it out.
//
// Its own module because it is its own capability, and the one before it (GymBuddy's) was app
// code another app would have had to copy. It costs an app nothing to link: no library (Android's
// RemoteViews and AppWidgetProvider, not Glance), no permission, and no manifest entry (the
// receiver names the app's own widget, so the app declares it).
//
// | Platform | WidgetBoard     | Drawn by                                                      |
// |----------|-----------------|---------------------------------------------------------------|
// | Android  | AndroidWidgets  | the app's SnapshotWidget subclass, with its own RemoteViews   |
// | iOS      | DarwinWidgets   | the app's WidgetKit extension (Swift), reading the App Group  |
// | Desktop  | not written     |                                                               |
library(namespace = "dev.shibasis.reaktor.widget", targets = Targets.Android + Targets.Apple + Targets.Jvm) {
    dependencies {
        common {
            api(projects.reaktorCore)
        }
    }
}
