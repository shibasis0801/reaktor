package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.common.Configuration
import dev.shibasis.dependeasy.plugins.getExtension
import dev.shibasis.dependeasy.tasks.darwinCmake
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

class DarwinConfigure(): Configuration<KotlinNativeTarget>() {
    var armOnly: Boolean = true
    internal var podDependencies: CocoapodsExtension.() -> Unit = {}
        private set
    internal var cinterops: NamedDomainObjectContainer<DefaultCInteropSettings>.() -> Unit = {}
        private set

    fun podDependencies(fn: CocoapodsExtension.() -> Unit = {}){
        this.podDependencies = fn
    }

    fun cinterops(fn: NamedDomainObjectContainer<DefaultCInteropSettings>.() -> Unit = {}) {
        this.cinterops = fn
    }
}

fun KotlinMultiplatformExtension.darwin(
    configuration: DarwinConfigure.() -> Unit = {}
) {
    val configure = DarwinConfigure().apply(configuration)

    val iosCmake = project.darwinCmake("iphoneos")
    val iosSimulatorCmake = project.darwinCmake("iphonesimulator")
    if (iosCmake != null && iosSimulatorCmake != null) {
        project.tasks.named("build") {
            dependsOn(iosCmake, iosSimulatorCmake)
        }
    }

    val targets = mutableListOf(
//        iosSimulatorArm64(),
        iosArm64()
    )

    // x64 cmake support later.
//    if (!configure.armOnly)
//        targets.apply {
//            add(iosX64())
//        }

    targets.forEach {
        configure.targetModifier(it)
        it.compilations.getByName("main").cinterops {
            configure.cinterops(this)
        }
    }

    getExtension<CocoapodsExtension>("cocoapods")?.apply {
        summary = "Some description for the Shared Module"
        homepage = "Link to the Shared Module homepage"
        version = "1.0"
        ios.deploymentTarget = Version.SDK.targetDarwin
        framework {
            isStatic = true
            freeCompilerArgs += listOf(
                "-Xbinary=stripDebugSymbols=true",
                "-Xbinary=stripDwarf=true"
            )
        }
        configure.podDependencies(this)
    }

    sourceSets {
        iosMain {
            configure.sourceSetModifier(this)
            dependencies {
                configure.dependencies(this)
            }
        }
    }
}
