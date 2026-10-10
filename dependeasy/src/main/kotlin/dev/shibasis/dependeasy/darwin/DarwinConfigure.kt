package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.common.Configuration
import dev.shibasis.dependeasy.native.nativeConfigurationOrNull
import dev.shibasis.dependeasy.native.nativeProjectDependencies
import dev.shibasis.dependeasy.tasks.darwinCmake
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

class DarwinConfigure : Configuration<KotlinNativeTarget>() {
    internal var cinterops: NamedDomainObjectContainer<DefaultCInteropSettings>.() -> Unit = {}
        private set

    fun cinterops(configure: NamedDomainObjectContainer<DefaultCInteropSettings>.() -> Unit = {}) {
        cinterops = configure
    }
}

fun KotlinMultiplatformExtension.darwin(configuration: DarwinConfigure.() -> Unit = {}) {
    val options = DarwinConfigure().apply(configuration)
    val native = project.nativeConfigurationOrNull
    val dependencies = native?.takeIf { it.isEnabled }?.let { project.nativeProjectDependencies() }.orEmpty()
    val builds = listOf("iphoneos", "iphonesimulator").associateWith(project::darwinCmake)
    if (builds.values.all { it != null }) project.tasks.named("build") { dependsOn(builds.values) }

    listOf(iosSimulatorArm64(), iosArm64()).forEach { target ->
        val sdk = if (target.name.lowercase().contains("simulator")) "iphonesimulator" else "iphoneos"
        val build = builds[sdk]
        build?.let { target.compilations.getByName("main").compileTaskProvider.configure { dependsOn(it) } }
        native?.takeIf { it.darwin.isConfigured }?.let {
            project.registerDarwinInterop(target, sdk, it, dependencies, build)
        }
        options.targetModifier(target)
        target.compilations.getByName("main").cinterops { options.cinterops(this) }
    }

    sourceSets.matching { it.name == "iosTest" }.configureEach {
        dependencies { options.testDependencies(this) }
    }
    sourceSets.matching { it.name == "iosMain" }.configureEach {
        options.sourceSetModifier(this)
        dependencies { options.dependencies(this) }
    }
}
