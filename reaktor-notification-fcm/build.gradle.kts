import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    // Firebase Cloud Messaging transport for :reaktor-notification.
    //
    // Split out of that module because depending on it is not free: firebase-messaging's manifest
    // merges INTERNET, WAKE_LOCK and com.google.android.c2dm.permission.RECEIVE into whatever app
    // links it, and drags ~8MB of dex along. An app that only wants a local reminder was paying all
    // of that and then having to explain the permissions on a store listing.
    //
    // Depend on this module only when the app actually receives remote push. Everything in
    // :reaktor-notification keeps working without it — see AndroidPushTransport there.
    module("dev.shibasis.reaktor.notification.fcm") {
        common {
            dependencies {
                api(project(":reaktor-notification"))
                commonCoroutines()
            }
        }

        android {
            dependencies {
                api(project.dependencies.platform(Versions.Android.FirebaseBom))
                api(Versions.Android.FirebaseMessaging)
            }
        }
        apple {}
    }
}
