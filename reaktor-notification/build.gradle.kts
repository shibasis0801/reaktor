import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization

import dev.shibasis.dependeasy.dependencies.useKoin

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.reaktor.notification") {
        common {
            dependencies {
                api(project(":reaktor-core"))
                api(project(":reaktor-graph"))
                api(project(":reaktor-ui"))
                commonCoroutines()
                commonSerialization()
            }
        }
        android {}
        apple {}

        web {
            dependencies {
                api(project(":reaktor-cloudflare"))
            }
        }
        jvm {}

        kotlin {
            // Local notifications only. The FCM transport — and with it firebase-messaging, the
            // FirebaseMessaging pod, and the INTERNET / c2dm.RECEIVE permissions they merge into an
            // app's manifest — lives in :reaktor-notification-fcm. Apps that receive remote push depend
            // on that module; apps that only schedule reminders no longer pay for it.

            useKoin()
        }
    }
}
