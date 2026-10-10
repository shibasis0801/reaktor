package dev.shibasis.dependeasy.darwin

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.konan.target.Family

/** Only an app or deliberate umbrella produces frameworks. Ordinary libraries produce klibs. */
fun KotlinMultiplatformExtension.appleFramework(
    name: String = "app",
    exports: List<Any> = emptyList(),
    transitiveExport: Boolean = false,
) {
    val xcframework = project.XCFramework(name)
    project.tasks.withType(dev.shibasis.dependeasy.tasks.ArtifactSizeReport::class.java).configureEach {
        dependsOn("assemble${name.replaceFirstChar(Char::uppercaseChar)}ReleaseXCFramework")
        artifacts.from(project.fileTree(project.layout.buildDirectory.dir("XCFrameworks/release/$name.xcframework")) {
            include("**/$name.framework/$name")
        })
    }
    targets.withType(KotlinNativeTarget::class.java).configureEach {
        if (konanTarget.family == Family.IOS) binaries.framework {
            baseName = name
            isStatic = true
            this.transitiveExport = transitiveExport
            exports.forEach { export(it) }
            linkerOpts("-lsqlite3", "-framework", "CoreLocation")
            binaryOption("bundleId", "${project.group}.${project.name}".replace('-', '.'))
            xcframework.add(this)
        }
    }
}
