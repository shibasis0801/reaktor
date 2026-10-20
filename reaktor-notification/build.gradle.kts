import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization
import dev.shibasis.dependeasy.common.commonKoin
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.compose-library") }

library(namespace = "dev.shibasis.reaktor.notification", targets = Targets.All) {
    // Local notifications only. The FCM transport — and with it firebase-messaging, the
    // FirebaseMessaging pod, and the INTERNET / c2dm.RECEIVE permissions they merge into an
    // app's manifest — lives in :reaktor-notification-fcm. Apps that receive remote push depend
    // on that module; apps that only schedule reminders no longer pay for it.
    dependencies {
        common {
            api(projects.reaktorCore)
            api(projects.reaktorGraph)
            api(projects.reaktorUi)
            commonCoroutines()
            commonSerialization()
            commonKoin()
        }
        web { api(projects.reaktorCloudflare) }
    }
}
