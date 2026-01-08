package dev.shibasis.dependeasy.dependencies

import dev.shibasis.dependeasy.Version
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
    version: String = Version.Koin,
    annotationVersion: String = Version.KoinAnnotations,
    transitive: Boolean = true // implement later
) {
    sourceSets {
        sources.forEach {
            when(it) {
                Source.Common -> commonMain.dependencies {
                    api("io.insert-koin:koin-core:$version")
                    api("io.insert-koin:koin-compose:$version")
                    api("io.insert-koin:koin-annotations:$annotationVersion")
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
    ktorVersion: String = Version.Ktor,
    okHttpVersion: String = Version.OkHttp,
    isTransitive: Boolean = true // implement later
) {
    withSources(sources) {
        when(it) {
            Source.Common -> commonMain.dependencies {
                dependency("io.ktor:ktor-client-core:$ktorVersion", isTransitive)
                dependency("io.ktor:ktor-client-content-negotiation:$ktorVersion", isTransitive)
                dependency("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion", isTransitive)
                dependency("io.ktor:ktor-client-logging:$ktorVersion")
            }
            Source.Web -> jsMain.dependencies {
                dependency("io.ktor:ktor-client-js:$ktorVersion", isTransitive)
            }
            Source.Darwin -> iosMain.dependencies {
                dependency("io.ktor:ktor-client-darwin:$ktorVersion", isTransitive)
            }
            Source.Android -> androidMain.dependencies {
                dependency("com.squareup.okhttp3:okhttp:$okHttpVersion", isTransitive)
                dependency("io.ktor:ktor-client-okhttp:$ktorVersion", isTransitive)
            }
            Source.Server -> jvmMain.dependencies {
                dependency("com.squareup.okhttp3:okhttp:$okHttpVersion", isTransitive)
                dependency("io.ktor:ktor-client-okhttp:$ktorVersion", isTransitive)
            }
        }
    }
}

