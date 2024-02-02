package dev.shibasis.dependeasy.common

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.android.androidCoroutines
import dev.shibasis.dependeasy.utils.installModules
import dev.shibasis.dependeasy.utils.module
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

fun commonKoinList(
    koinVersion: String = Version.Koin,
    koinAnnotationVersion: String = Version.KoinAnnotations
) = listOf(
    module("io.insert-koin:koin-core:$koinVersion"),
//    module("io.insert-koin:koin-annotations:$koinAnnotationVersion")
)

fun koinList(koinVersion: String) = listOf(
    module("io.insert-koin:koin-android:$koinVersion"),
    module("io.insert-koin:koin-android-compat:$koinVersion"),
    module("io.insert-koin:koin-androidx-workmanager:$koinVersion"),
    module("io.insert-koin:koin-androidx-navigation:$koinVersion"),
    module("io.insert-koin:koin-androidx-compose:$koinVersion"),
)

fun KotlinDependencyHandler.androidKoin(
    koinVersion: String = Version.Koin,
    koinAnnotationVersion: String = Version.KoinAnnotations
) {
    installModules(commonKoinList(koinVersion, koinAnnotationVersion))
    installModules(koinList(koinVersion))
}

fun KotlinDependencyHandler.commonKoin(
    koinVersion: String = Version.Koin,
    koinAnnotationVersion: String = Version.KoinAnnotations
) = installModules(commonKoinList(koinVersion, koinAnnotationVersion))

fun DependencyHandlerScope.enableAnnotations(koinAnnotationVersion: String = Version.KoinAnnotations) {
    listOf(
        "kspCommonMainMetadata",
        "kspAndroid",
        "kspIosArm64",
        "kspIosX64",
        "kspJs"
    ).forEach {
            println(it)
            add(it, "io.insert-koin:koin-ksp-compiler:1.0.1")
        }
}
