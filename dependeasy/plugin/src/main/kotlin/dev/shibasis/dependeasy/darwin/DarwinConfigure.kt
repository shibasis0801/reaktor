package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.plugins.getExtension
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.kotlin.dsl.creating
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.cocoapods.CocoapodsExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

class DarwinConfigure(
    var devBuild: Boolean = false,
    var dependencies: KotlinDependencyHandler.() -> Unit = {},
    var podDependencies: CocoapodsExtension.() -> Unit = {},
    var cinterops: NamedDomainObjectContainer<DefaultCInteropSettings>.() -> Unit = {},
    var targets: KotlinNativeTarget.() -> Unit = {}
)

fun KotlinMultiplatformExtension.darwin(
    configuration: DarwinConfigure.() -> Unit = {}
) {
    val configure = DarwinConfigure().apply(configuration)

    val targets = mutableListOf<KotlinNativeTarget>(
        iosSimulatorArm64()
    )

    if (!configure.devBuild)
        targets.apply {
            add(iosArm64())
            add(iosX64())
        }

    targets.forEach {
        configure.targets(it)
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
        }
        configure.podDependencies(this)
    }

    sourceSets {
        iosMain {
            dependencies {
                configure.dependencies(this)
            }
        }
    }
}
