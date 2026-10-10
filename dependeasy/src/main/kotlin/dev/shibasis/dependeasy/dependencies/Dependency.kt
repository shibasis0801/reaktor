package dev.shibasis.dependeasy.dependencies

import dev.shibasis.dependeasy.Versions
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

enum class Source { Common, Android, Darwin, Web, Server }
val CommonSourceList = listOf(Source.Common)
val MobileSourceList = CommonSourceList + listOf(Source.Android) + listOf(Source.Darwin)
val ClientSourceList = MobileSourceList + Source.Web
val AllSourceList = ClientSourceList + Source.Server

fun KotlinMultiplatformExtension.useKoin(
    sources: List<Source> = CommonSourceList,
    version: String = Versions.Koin,
    annotationVersion: String = Versions.KoinAnnotations,
    transitive: Boolean = true // implement later
) {
    sourceSets {
        sources.forEach {
            when(it) {
                Source.Common -> commonMain.dependencies {
                    api(Versions.Kotlin.KoinCore.atVersion(version))
                    api(Versions.Kotlin.KoinCompose.atVersion(version))
                    api(Versions.Kotlin.KoinAnnotations.atVersion(annotationVersion))
                }
                else -> {}
            }
        }
    }
}


fun KotlinDependencyHandler.dependency(dependencyNotation: String, isTransitive: Boolean = true, configure: ExternalModuleDependency.() -> Unit = {}) {
    if (isTransitive)
        api(dependencyNotation, configure)
    else
        implementation(dependencyNotation, configure)
}

fun KotlinMultiplatformExtension.withSources(
    sources: List<Source> = AllSourceList,
    fn: NamedDomainObjectContainer<KotlinSourceSet>.(Source) -> Unit
) {
    sourceSets {
        sources.forEach { fn(it) }
    }
}

fun KotlinMultiplatformExtension.useNetworking(
    sources: List<Source> = AllSourceList,
    ktorVersion: String = Versions.Ktor,
    okHttpVersion: String = Versions.OkHttp,
    isTransitive: Boolean = true // implement later
) {
    withSources(sources) {
        when(it) {
            Source.Common -> commonMain.dependencies {
                dependency(Versions.Kotlin.KtorCore.atVersion(ktorVersion), isTransitive)
                dependency(Versions.Kotlin.KtorContentNegotiation.atVersion(ktorVersion), isTransitive)
                dependency(Versions.Kotlin.KtorJson.atVersion(ktorVersion), isTransitive)
                dependency(Versions.Kotlin.KtorLogging.atVersion(ktorVersion))
            }
            Source.Web -> jsMain.dependencies {
                dependency(Versions.Kotlin.KtorJs.atVersion(ktorVersion), isTransitive)
            }
            Source.Darwin -> iosMain.dependencies {
                dependency(Versions.Kotlin.KtorDarwin.atVersion(ktorVersion), isTransitive)
            }
            Source.Android -> androidMain.dependencies {
                dependency(Versions.Data.OkHttp.atVersion(okHttpVersion), isTransitive)
                dependency(Versions.Kotlin.KtorOkHttp.atVersion(ktorVersion), isTransitive)
            }
            Source.Server -> jvmMain.dependencies {
                dependency(Versions.Data.OkHttp.atVersion(okHttpVersion), isTransitive)
                dependency(Versions.Kotlin.KtorOkHttp.atVersion(ktorVersion), isTransitive)
            }
        }
    }
}

