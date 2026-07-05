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
        dependencies {
            implementation(project.dependencies.platform("com.google.firebase:firebase-bom:33.1.1"))
            implementation("com.google.firebase:firebase-messaging")
        }
    }
    darwin {
        podDependencies {
            // FCM transport (DarwinRemoteMessaging) lives in this module now — these pods
            // moved out of the app. NOTE: the Xcode 26 "Catalyst-only" xcodebuild workaround
            // tasks in the app's build.gradle.kts (`darwinPods` loop) must be replicated here
            // for FirebaseCore + FirebaseMessaging, and shared FirebaseCore reconciled with
            // reaktor-telemetry (Analytics/Crashlytics) — verify with an on-device iOS build.
            pod("FirebaseCore") {
                version = "11.0"
                extraOpts += listOf("-compiler-option", "-fmodules")
            }
            pod("FirebaseMessaging") {
                version = "11.0"
                extraOpts += listOf("-compiler-option", "-fmodules")
            }
        }
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
