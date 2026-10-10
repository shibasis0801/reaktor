package dev.shibasis.dependeasy.interop

import dev.shibasis.dependeasy.native.NativeConfiguration
import dev.shibasis.dependeasy.plugins.DependeasyExtension
import org.gradle.api.Project

/** Native sources follow Kotlin source sets; mobile TypeScript is an ordinary pnpm/Vite component. */
class InteropBuild internal constructor(private val project: Project, private val name: String) {
    internal val nativeInstallers = linkedMapOf<String, String>()
    internal var typescriptDirectory: Any? = null
    internal var bundle = "${project.name}.js"
    internal var packageName = "${project.group}.${project.name.replace('-', '.')}.interop.generated"
    internal var hostTests = project.file("src/commonTest/cpp").isDirectory

    fun cpp(configuration: NativeConfiguration.() -> Unit = {}) {
        DependeasyExtension.get(project).nativeLibrary(configuration)
    }
    fun typescript(directory: Any = ".", bundle: String = this.bundle,
                   packageName: String = this.packageName) {
        typescriptDirectory = directory
        this.bundle = bundle
        this.packageName = packageName
    }
    /** Compose exports into each runtime owned by this module. The installer accepts Runtime&. */
    fun cppModule(header: String, installer: String) {
        require(nativeInstallers.putIfAbsent(header, installer) == null) { "C++ module '$header' is already declared" }
    }
    fun hostTests(enabled: Boolean) { hostTests = enabled }
    internal fun install() = InteropBuildKernel(project, name, this).install()
}
