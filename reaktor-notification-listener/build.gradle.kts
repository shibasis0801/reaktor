import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

// Reading other apps' notifications, with the user's grant: Android's NotificationListenerService.
//
// A module of its own, not a file in :reaktor-notification, because depending on it is not free.
// Its manifest declares a listener service, which puts the app in Settings → Notification access
// as one that can read every notification, and Play Protect treats a sideloaded app that declares
// a listener as high-risk. An app that only posts a reminder must not inherit that. It needs none
// of :reaktor-notification's weight either (reaktor-security, reaktor-cloudflare on the web).
//
// Interface plus slot: only Android has listeners. iOS and the desktop leave
// Feature.NotificationListener empty; there is no web target until an app with one needs it
// (adding it changes the pnpm lockfile every JS build shares). See plans/coffer.md §4.1.
library(namespace = "dev.shibasis.reaktor.notification.listener", targets = Targets.Android + Targets.Apple + Targets.Jvm) {
    dependencies {
        common {
            api(projects.reaktorCore)
            commonCoroutines()
        }
    }
}
