import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

// Sealed notices opened on the device: a push that carries an end-to-end encrypted message, and
// the keys kept on the phone to turn it into a preview ("Asha: on my way") without a server ever
// reading it.
//
// Split out of :reaktor-notification because opening one needs :reaktor-security, which compiles
// BoringSSL and MLS++ into every app that links it: about 2.6 MB per ABI of libReaktorSecurity.so.
// An app that only schedules a reminder was shipping all of that. This is the same split
// :reaktor-notification-fcm made for Firebase.
//
// What stays in :reaktor-notification costs nothing: SealedNotice (the wire shape a server
// filters and sizes) and NoticePreviews (the hook a notification client calls when one arrives).
// This module holds what implements the hook: SealedNoticePreviews and its per-platform stores.
// The package is :reaktor-notification's own, as with the FCM module, so an app that moves its
// dependency here changes no import.
//
// Depend on it only when the app shows previews of sealed messages. The targets are
// :reaktor-security's: an app's common code names SealedNoticePreviews, so it has to exist on
// each of them.
library(namespace = "dev.shibasis.reaktor.notification.sealed", targets = Targets.Android + Targets.Apple + Targets.Jvm + Targets.Web) {
    dependencies {
        common {
            api(projects.reaktorNotification)
            api(projects.reaktorSecurity)
            commonCoroutines()
            commonSerialization()
        }
    }
}
