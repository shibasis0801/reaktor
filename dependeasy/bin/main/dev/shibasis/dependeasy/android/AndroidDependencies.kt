package dev.shibasis.dependeasy.android

import org.gradle.api.Project
import org.gradle.kotlin.dsl.DependencyHandlerScope
import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.utils.*
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

fun KotlinDependencyHandler.activityFragment(
    activity_version: String = Version.Activity,
    fragment_version: String = Version.Fragment
) = installModules(module("org.jetbrains.kotlin:kotlin-stdlib:${Version.SDK.Kotlin}"),
    module("androidx.activity:activity:${Version.Activity}"),
    module("androidx.activity:activity-ktx:${Version.Activity}"),
    module("androidx.fragment:fragment:${Version.Fragment}"),
    module("androidx.fragment:fragment-ktx:${Version.Fragment}"),
    module("androidx.activity:activity-compose:${Version.Activity}")

)

fun KotlinDependencyHandler.lifecycle(
    lifecycle_version: String = Version.Lifecycle
) = installModules(
    module("androidx.lifecycle:lifecycle-service:$lifecycle_version"),
    module("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycle_version"),
    module("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycle_version"),
    module("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle_version"),
    module("androidx.lifecycle:lifecycle-viewmodel-savedstate:$lifecycle_version"),
    module("androidx.lifecycle:lifecycle-common-java8:$lifecycle_version"),
    module("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycle_version")
)

fun KotlinDependencyHandler.androidNetworking(
) = installModules(
    module("com.squareup.okhttp3:okhttp:${Version.OkHttp}"),
    module("io.ktor:ktor-client-okhttp:${Version.Ktor}")
)


fun KotlinDependencyHandler.workManager(
    work_version: String = Version.WorkManager
) = installModules(module("androidx.work:work-runtime-ktx:${Version.WorkManager}"))

fun KotlinDependencyHandler.extensions() = installModules(
    module("androidx.core:core-ktx:1.7.0"),
    module("androidx.collection:collection-ktx:1.2.0")
)


fun KotlinDependencyHandler.camera() = installModules(
    module("androidx.camera:camera-camera2:${Version.CameraX}"),
    module("androidx.camera:camera-core:${Version.CameraX}"),
    module("androidx.camera:camera-video:${Version.CameraX}"),
    module("androidx.camera:camera-lifecycle:${Version.CameraX}"),
    module("androidx.camera:camera-view:${Version.CameraX}"),
    module("androidx.camera:camera-extensions:${Version.CameraX}"),
)

fun KotlinDependencyHandler.firebase(
    project: Project,
    minimal: Boolean = true
) {
    val firebaseBOM = "com.google.firebase:firebase-bom:${Version.Firebase}"
    implementation(project.dependencies.enforcedPlatform(firebaseBOM))

    if (minimal) {
        installModules(
            module("com.google.firebase:firebase-analytics-ktx"),
            module("com.google.firebase:firebase-crashlytics-ktx"),
            module("co.touchlab:kermit-crashlytics:${Version.Kermit}")
        )
    }
    else {
        installModules(
            module("com.google.firebase:firebase-auth-ktx"),
            module("com.google.firebase:firebase-config-ktx"),
            module("com.google.firebase:firebase-analytics-ktx"),
            module("com.google.firebase:firebase-crashlytics-ktx"),
            module("com.google.firebase:firebase-messaging-ktx"),
            module("com.google.android.gms:play-services-auth:20.1.0"),
        )
    }
}

fun KotlinDependencyHandler.androidCoroutines() = installModules(
    module("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Version.Coroutines}"),
    module("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:${Version.Coroutines}"),
    module("org.jetbrains.kotlinx:kotlinx-coroutines-guava:${Version.Coroutines}"),

)

fun KotlinDependencyHandler.fbjni() {
    api("com.facebook.fbjni:fbjni:0.2.2")
}