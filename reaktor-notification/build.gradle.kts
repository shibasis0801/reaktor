import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
    
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api(project(":reaktor-graph"))
            api(project(":reaktor-ui"))
            commonCoroutines()
            commonSerialization()
        }
    }
    droid {
    }
    // Local notifications only. The FCM transport — and with it firebase-messaging, the
    // FirebaseMessaging pod, and the INTERNET / c2dm.RECEIVE permissions they merge into an
    // app's manifest — lives in :reaktor-notification-fcm. Apps that receive remote push depend
    // on that module; apps that only schedule reminders no longer pay for it.
    darwin {
    }
    web {
        dependencies {
            api(project(":reaktor-cloudflare"))
        }
    }
    server {

    }
    useKoin()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            commonCoroutines()
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.notification")
}
