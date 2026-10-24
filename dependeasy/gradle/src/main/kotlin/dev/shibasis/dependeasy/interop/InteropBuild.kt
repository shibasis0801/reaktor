package dev.shibasis.dependeasy.interop

import dev.shibasis.dependeasy.components.LibraryDsl
import dev.shibasis.dependeasy.model.FileSet
import org.gradle.api.Project

/** Native sources follow Kotlin source sets; mobile TypeScript is an ordinary pnpm/Vite component. */
@LibraryDsl
class InteropBuild internal constructor(private val project: Project, private val name: String) {
    internal val nativeInstallers = linkedMapOf<String, String>()
    internal var typescriptDirectory: String? = null
    internal lateinit var typescriptTests: FileSet
    internal var bundle = "${project.name}.js"
    internal var packageName = "${project.group}.${project.name.replace('-', '.')}.interop.generated"
    internal var hostTests = false

    fun typescript(tests: FileSet, directory: String = ".", bundle: String = this.bundle,
                   packageName: String = this.packageName) {
        typescriptDirectory = directory
        typescriptTests = tests
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
