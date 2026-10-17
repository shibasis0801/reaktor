package dev.shibasis.dependeasy.android

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.dependencies.atVersion
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

fun KotlinDependencyHandler.activityFragment(activity_version: String = Versions.Activity, fragment_version: String = Versions.Fragment) {
    api(Versions.Kotlin.Stdlib)
    listOf(Versions.Android.Activity, Versions.Android.ActivityKtx, Versions.Android.ActivityCompose)
        .forEach { api(it.atVersion(activity_version)) }
    listOf(Versions.Android.Fragment, Versions.Android.FragmentKtx).forEach { api(it.atVersion(fragment_version)) }
}

fun KotlinDependencyHandler.lifecycle(lifecycle_version: String = Versions.Lifecycle) {
    Versions.Android.Lifecycle.forEach { api(it.atVersion(lifecycle_version)) }
}

fun KotlinDependencyHandler.androidNetworking() {
    api(Versions.Data.OkHttp)
    api(Versions.Kotlin.KtorOkHttp)
}

fun KotlinDependencyHandler.workManager(work_version: String = Versions.WorkManager) { api(Versions.Android.WorkManager.atVersion(work_version)) }
fun KotlinDependencyHandler.extensions() { api(Versions.Android.CoreKtx); api(Versions.Android.CollectionKtx) }
fun KotlinDependencyHandler.camera() { Versions.Android.Camera.forEach { api(it) } }

fun KotlinDependencyHandler.firebase(project: Project, minimal: Boolean = true) {
    implementation(project.dependencies.enforcedPlatform(Versions.Android.FirebaseBom))
    if (minimal) {
        api(Versions.Android.FirebaseCrashlyticsKtx)
        api(Versions.Kotlin.KermitCrashlytics)
    } else {
        listOf(Versions.Android.FirebaseAuthKtx, Versions.Android.FirebaseConfigKtx, Versions.Android.FirebaseCrashlyticsKtx,
            Versions.Android.FirebaseMessagingKtx, Versions.Android.GoogleAuth).forEach { api(it) }
    }
}

fun KotlinDependencyHandler.androidCoroutines() {
    listOf(Versions.Kotlin.CoroutinesAndroid, Versions.Kotlin.CoroutinesPlayServices, Versions.Kotlin.CoroutinesGuava).forEach { api(it) }
}

fun KotlinDependencyHandler.fbjni() { api(Versions.Native.FbjniJava) }
