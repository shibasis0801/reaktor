package dev.shibasis.dependeasy.toolchain

import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import dev.shibasis.dependeasy.model.ToolHandle

class VerificationTools private constructor(private val project: Project) {
    private val host = (if (System.getProperty("os.name").startsWith("Mac")) "darwin" else "linux") +
        "-" + if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "amd64"
    private fun install(name: String, version: String, url: String, sha: String, prefix: String?, binary: String, format: ToolArchive): InstalledTool {
        val directory = project.gradle.gradleUserHomeDir.resolve("dependeasy/tools/$name/$version/$host")
        val task = project.tasks.register<InstallTool>("${name}Setup") {
            this.url.set(url); checksum.set(sha); binaryName.set(binary); this.format.set(format)
            if (prefix != null) archivePrefix.set(prefix) else if (format != ToolArchive.RawFile) archiveEntry.set(binary)
            destination.set(directory)
        }
        val workspace = dev.shibasis.dependeasy.workspace.BuildWorkspace.get(project)
        workspace.reference(project, task, dev.shibasis.dependeasy.model.OutputKind.GeneratedContract, if (name == "maestroRunner")
            dev.shibasis.dependeasy.model.HostRequirement(execution = dev.shibasis.dependeasy.model.ExecutionHost.Local) else null)
        return InstalledTool(ToolHandle(name, version), project.objects.fileProperty().fileValue(directory.resolve(binary)), task, project.provider { emptyMap() })
    }
    val dagger: InstalledTool by lazy { install("dagger", ToolchainVersions.Dagger,
        "https://github.com/dagger/dagger/releases/download/v${ToolchainVersions.Dagger}/dagger_v${ToolchainVersions.Dagger}_${host.replace('-', '_')}.tar.gz",
        mapOf("darwin-arm64" to "f3f37a831afd53d09bf9c9a9df63492c16ad43622e5c197d9815e82f334ef1c4",
            "darwin-amd64" to "4759befe3fa951fd2ba05551306023df7e8c7fc9eff57bb823481803cb44b215",
            "linux-amd64" to "53e226c7da8fb75171e58c35759d736d961ce8b3a12db0baa7b7107954fccc5a",
            "linux-arm64" to "cd0df4885f2050082932b4abc5a6aad9a733f6aa4e7d8474740558517ffec4af").getValue(host),
        null, "dagger", ToolArchive.TarGzip) }
    val karate: InstalledTool by lazy { install("karate", "2.0.0",
        "https://github.com/karatelabs/karate/releases/download/v2.0.0/karate-2.0.0.jar",
        "fcbd3fb0e66a848f509dfb5f355f5176c16a29a3d9c4ba2a7f47e042f293bc78", null, "karate.jar", ToolArchive.RawFile) }
    val k6: InstalledTool by lazy {
        val platform = host.replace("darwin", "macos")
        val format = if (host.startsWith("darwin")) ToolArchive.Zip else ToolArchive.TarGzip
        val sha = mapOf(
            "darwin-arm64" to "7388e449816fdb98afe65f349f3983304ba5e14f73b6bee0d7f96c4e3e6b8942",
            "darwin-amd64" to "a600f44ad411ad5f5f7d178405d9956dac34c43563341396f1017ae7f79221a9",
            "linux-amd64" to "295d961ebfca306f295f1133068dcd403a8171c87f387928f5f30b0fbcff858a",
            "linux-arm64" to "191fa8d89512a4e5083f3fabcb4c3828af9f5b9eee016de8443f6473c029ffb5").getValue(host)
        install("k6", "2.1.0", "https://github.com/grafana/k6/releases/download/v2.1.0/k6-v2.1.0-$platform.${if (format == ToolArchive.Zip) "zip" else "tar.gz"}",
            sha, "k6-v2.1.0-$platform/", "k6", format)
    }
    val maestro: InstalledTool by lazy { install("maestroRunner", "1.1.27",
        "https://github.com/devicelab-dev/maestro-runner/releases/download/v1.1.27/maestro-runner-1.1.27-$host.tar.gz",
        mapOf("darwin-arm64" to "223615595dd9b2534f3987bab9a02200c18f685e7a99f20b48279ea72534db6d",
            "darwin-amd64" to "aade54c71cd5c7652b23ce6983cbf6d3a208f4900a7e476ca62cb393e2f6efd4",
            "linux-amd64" to "886c5dc0c6c84f697c5814af9044b273837115a7775b0bda16ce9e8324f17b4e",
            "linux-arm64" to "c36a132e0d8e6243cf075994e099225d353e99e35fa31d405eb4f61026228ae0").getValue(host),
        "maestro-runner/", "bin/maestro-runner", ToolArchive.TarGzip) }
    companion object {
        fun get(project: Project): VerificationTools = project.rootProject.run {
            extensions.findByType(VerificationTools::class.java) ?: VerificationTools(this).also { extensions.add("dependeasyVerificationTools", it) }
        }
    }
}
