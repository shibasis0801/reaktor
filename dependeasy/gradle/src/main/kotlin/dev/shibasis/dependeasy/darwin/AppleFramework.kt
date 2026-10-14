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
    val flags = listOf("-lsqlite3", "-lc++", "-framework", "CoreLocation")
    project.extensions.add("dependeasyAppleFramework", AppleFrameworkDeclaration(name, exports, flags))
    val xcframework = project.XCFramework(name)
    val frameworkName = name
    val release = project.tasks.withType(org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkTask::class.java).matching {
        it.buildType == org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE && it.baseName.get() == frameworkName
    }
    val files = project.files(release.map { it.outputs.files }).builtBy(release)
    project.tasks.withType(dev.shibasis.dependeasy.tasks.ArtifactSizeReport::class.java).configureEach {
        dependsOn(release)
        artifacts.from(files.asFileTree.matching { include("**/$frameworkName.framework/$frameworkName") })
    }
    targets.withType(KotlinNativeTarget::class.java).configureEach {
        if (konanTarget.family == Family.IOS) binaries.framework {
            baseName = name
            isStatic = true
            this.transitiveExport = transitiveExport
            exports.forEach { export(if (it is dev.shibasis.dependeasy.model.ModuleHandle) it.coordinate else it) }
            linkerOpts(flags)
            binaryOption("bundleId", "${project.group}.${project.name}".replace('-', '.'))
            xcframework.add(this)
        }
    }
}
