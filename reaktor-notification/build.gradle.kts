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
    //
    // The same goes for sealed notices. Opening one needs :reaktor-security (BoringSSL and MLS++,
    // about 2.6 MB of native code per ABI), so SealedNoticePreviews lives in
    // :reaktor-notification-sealed and this module keeps only the hook it plugs into
    // (NoticePreviews) and the wire shape (SealedNotice).
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
