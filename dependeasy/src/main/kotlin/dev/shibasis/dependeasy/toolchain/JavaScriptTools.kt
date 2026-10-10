package dev.shibasis.dependeasy.toolchain

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.register

class JavaScriptTools private constructor(private val project: Project) {
    private val cache = project.gradle.gradleUserHomeDir.resolve("dependeasy/tools")
    private val platform = when {
        System.getProperty("os.name").contains("Mac") -> "darwin"
        System.getProperty("os.name").contains("Linux") -> "linux"
        else -> error("Dependeasy JavaScript tools require macOS or Linux")
    } + "-" + when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> error("Unsupported JavaScript tool architecture")
    }
    private fun setup(name: String, version: String, download: String, entry: String): TaskProvider<InstallTool> =
        project.tasks.register<InstallTool>("${name}Setup") {
            group = "dependeasy"
            url.set(download); checksum.set(JavaScriptChecksums.get(name, platform))
            archiveEntry.set(entry); binaryName.set(name)
            destination.set(cache.resolve("$name/$version/$platform/bin"))
        }
    val nodeSetup = setup("node", ToolchainVersions.Node,
        "https://nodejs.org/dist/v${ToolchainVersions.Node}/node-v${ToolchainVersions.Node}-$platform.tar.gz",
        "node-v${ToolchainVersions.Node}-$platform/bin/node")
    val pnpmSetup = setup("pnpm", ToolchainVersions.Pnpm,
        "https://registry.npmjs.org/@pnpm/exe.$platform/-/exe.$platform-${ToolchainVersions.Pnpm}.tgz", "package/pnpm")
    // Kotlin asks for the executable during graph construction, before setup tasks run.
    val node: Provider<RegularFile> = project.objects.fileProperty().fileValue(cache.resolve("node/${ToolchainVersions.Node}/$platform/bin/node"))
    val pnpm: Provider<RegularFile> = project.objects.fileProperty().fileValue(cache.resolve("pnpm/${ToolchainVersions.Pnpm}/$platform/bin/pnpm"))
    val path: Provider<String> = node.zip(pnpm) { node, pnpm ->
        listOf(node.asFile.parent, pnpm.asFile.parent, System.getenv("PATH"))
            .joinToString(java.io.File.pathSeparator)
    }
    val export: TaskProvider<Sync> = project.tasks.register<Sync>("javascriptToolchain") {
        group = "dependeasy"
        description = "Export the verified Node and pnpm binaries for CI container adapters"
        dependsOn(nodeSetup, pnpmSetup)
        from(node, pnpm)
        into(project.layout.buildDirectory.dir("dependeasy/tools/javascript/bin"))
    }

    companion object {
        fun get(project: Project): JavaScriptTools = project.rootProject.run {
            extensions.findByType(JavaScriptTools::class.java) ?: JavaScriptTools(this).also { extensions.add("dependeasyJavaScriptTools", it) }
        }
    }
}
