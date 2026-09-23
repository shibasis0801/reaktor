import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
}

// Firebase Cloud Messaging transport for :reaktor-notification.
//
// Split out of that module because depending on it is not free: firebase-messaging's manifest
// merges INTERNET, WAKE_LOCK and com.google.android.c2dm.permission.RECEIVE into whatever app
// links it, and drags ~8MB of dex along. An app that only wants a local reminder was paying all
// of that and then having to explain the permissions on a store listing.
//
// Depend on this module only when the app actually receives remote push. Everything in
// :reaktor-notification keeps working without it — see AndroidPushTransport there.
kotlin {
    common {
        dependencies {
            api(project(":reaktor-notification"))
            commonCoroutines()
        }
    }
    droid {
        dependencies {
            implementation(project.dependencies.platform("com.google.firebase:firebase-bom:33.1.1"))
            api("com.google.firebase:firebase-messaging")
        }
    }
    darwin {
        dependencies {
            api("dev.gitlive:firebase-app:2.4.0")
        }
        podDependencies {
            // NOTE: the Xcode 26 "Catalyst-only" xcodebuild workaround tasks that apps carry in
            // their `darwinPods` loop must cover FirebaseCore + FirebaseMessaging, and shared
            // FirebaseCore reconciled with reaktor-telemetry (Analytics/Crashlytics).
            // Verify with an on-device iOS build.
            pod("FirebaseMessaging") {
                version = "11.0"
                extraOpts += listOf("-compiler-option", "-fmodules")
            }
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.notification.fcm")
}
