package dev.shibasis.dependeasy.darwin

import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** An Apple framework can stand alone without Android, Compose, or a product application. */
internal fun Project.appleLibrary(name: String, exports: List<Any>, extensionSafe: Boolean,
                                 configure: KotlinMultiplatformExtension.() -> Unit) {
    pluginManager.apply("org.jetbrains.kotlin.multiplatform")
    extensions.getByType<KotlinMultiplatformExtension>().apply {
        iosArm64()
        iosSimulatorArm64()
        configure()
        sourceSets.getByName("commonMain").dependencies { exports.forEach(::api) }
        appleFramework(name, exports)
        if (extensionSafe) targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java)
            .configureEach { binaries.withType(org.jetbrains.kotlin.gradle.plugin.mpp.Framework::class.java)
                .configureEach { linkerOpts("-application_extension") } }
    }
}
