import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

// Firebase Cloud Messaging transport for :reaktor-notification.
//
// Split out of that module because depending on it is not free: firebase-messaging's manifest
// merges INTERNET, WAKE_LOCK and com.google.android.c2dm.permission.RECEIVE into whatever app
// links it, and drags ~8MB of dex along. An app that only wants a local reminder was paying all
// of that and then having to explain the permissions on a store listing.
//
// Depend on this module only when the app actually receives remote push. Everything in
// :reaktor-notification keeps working without it — see AndroidPushTransport there.
library(namespace = "dev.shibasis.reaktor.notification.fcm", targets = Targets.Android + Targets.Apple) {
    dependencies {
        common {
            api(projects.reaktorNotification)
            commonCoroutines()
        }
        android {
            api(project.dependencies.platform(Versions.Android.FirebaseBom))
            api(Versions.Android.FirebaseMessaging)
        }
    }
}
