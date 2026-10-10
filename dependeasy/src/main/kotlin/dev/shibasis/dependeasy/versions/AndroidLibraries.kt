package dev.shibasis.dependeasy.versions

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

object AndroidLibraries {
    const val CoreKtx = "androidx.core:core-ktx:1.7.0"
    const val CollectionKtx = "androidx.collection:collection-ktx:1.2.0"
    const val ActivityKtx = "androidx.activity:activity-ktx:${Versions.Activity}"
    const val ActivityCompose = "androidx.activity:activity-compose:${Versions.Activity}"
    const val Fragment = "androidx.fragment:fragment:${Versions.Fragment}"
    const val FragmentKtx = "androidx.fragment:fragment-ktx:${Versions.Fragment}"
    const val FirebaseAuthKtx = "com.google.firebase:firebase-auth-ktx"
    const val FirebaseConfigKtx = "com.google.firebase:firebase-config-ktx"
    const val FirebaseCrashlyticsKtx = "com.google.firebase:firebase-crashlytics-ktx"
    const val FirebaseMessagingKtx = "com.google.firebase:firebase-messaging-ktx"
    const val GoogleAuth = "com.google.android.gms:play-services-auth:20.1.0"
    const val Desugaring = "com.android.tools:desugar_jdk_libs:2.1.4"
    val Lifecycle = listOf("service", "livedata-ktx", "viewmodel-ktx", "runtime-ktx", "viewmodel-savedstate", "common-java8", "viewmodel-compose")
        .map { "androidx.lifecycle:lifecycle-$it:${Versions.Lifecycle}" }
    val Camera = listOf("camera2", "core", "video", "lifecycle", "view", "extensions")
        .map { "androidx.camera:camera-$it:${Versions.CameraX}" }
    const val WebView = "androidx.webkit:webkit:${Versions.WebView.AndroidX}"
    const val Activity = "androidx.activity:activity:${Versions.Activity}"
    const val Credentials = "androidx.credentials:credentials:1.3.0"
    const val CredentialsPlayServices = "androidx.credentials:credentials-play-services-auth:1.3.0"
    const val FirebaseBom = "com.google.firebase:firebase-bom:${Versions.Firebase}"
    const val FirebaseCrashlytics = "com.google.firebase:firebase-crashlytics"
    const val FirebaseKotlinCrashlytics = "dev.gitlive:firebase-crashlytics:2.4.0"
    const val FirebaseMessaging = "com.google.firebase:firebase-messaging"
    const val GoogleId = "com.google.android.libraries.identity.googleid:googleid:1.1.1"
    const val HealthConnect = "androidx.health.connect:connect-client:1.1.0-alpha07"
    const val Location = "com.google.android.gms:play-services-location:21.2.0"
    const val SQLite = "androidx.sqlite:sqlite-framework:2.4.0"
    const val TestCoreKtx = "androidx.test:core-ktx:1.5.0"
    const val TestJUnitLegacy = "androidx.test.ext:junit:1.1.5"
    const val TestJUnitKtx = "androidx.test.ext:junit-ktx:1.1.5"
    const val Espresso = "androidx.test.espresso:espresso-core:3.5.1"
    const val TestJUnit = "androidx.test.ext:junit:${Versions.WebView.AndroidTestJUnit}"
    const val TestRunner = "androidx.test:runner:${Versions.WebView.AndroidTestRunner}"
    const val WorkManager = "androidx.work:work-runtime-ktx:${Versions.WorkManager}"
}
