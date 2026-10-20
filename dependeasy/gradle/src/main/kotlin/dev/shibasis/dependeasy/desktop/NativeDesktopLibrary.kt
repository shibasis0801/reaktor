package dev.shibasis.dependeasy.desktop

import dev.shibasis.dependeasy.toolchain.buildHost
import dev.shibasis.dependeasy.workspace.declarationSite
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.model.NativeContract
import dev.shibasis.dependeasy.native.NativeDependency
import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal class NativeDesktopLibrary(private val project: Project, private val name: String,
    private val source: String, private val target: String, private val defines: Map<String, String>, private val sources: List<NativeDependency>) {

    internal fun register() = with(project) {
        val site = declarationSite()
        val host = buildHost
        val platform = (if (host.mac) "macos-" else if (host.windows) "windows-" else "linux-") +
            (if (host.arm) "arm64" else "x64")
        val libraryTaskName = this@NativeDesktopLibrary.name
        val output = layout.buildDirectory.dir("libs/$libraryTaskName/$platform")
        val sourceRoot = layout.buildDirectory.dir("dependeasy/sources/$libraryTaskName")
        val sourceDeclarations = sources.map { dependency -> NativeContract.Source(dependency.name, dependency.repository,
            dependency.revision, dependency.checksum, dependency.checksumAlgorithm, dependency.recipe,
            sourceRoot.get().dir("${dependency.name}-${dependency.revision}").asFile.absolutePath) }
        val jdk = extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
        }
        val compile = tasks.register<KotlinCMakeTask>(libraryTaskName) {
            group = "dependeasy"
            site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
            sourceDirectory.set(file(source))
            sourceFiles.from(fileTree(file(source)))
            buildDirectory.set(layout.buildDirectory.dir("native/$libraryTaskName/$platform"))
            generator.set("Ninja")
            buildTarget.set(target)
            val libraryName = target
            val declarations = defines.toMap()
            nativeContract.set(dev.shibasis.dependeasy.tasks.CmakePlatform.Host().settings(project).map { settings ->
                BuildModelReader.json.encodeToString(NativeContract(library = libraryName, platform = platform, sources = sourceDeclarations,
                    settings = settings + declarations + mapOf("CMAKE_BUILD_TYPE" to "Release", "CMAKE_CXX_STANDARD" to ToolchainVersions.CppStandard, "CMAKE_OBJCXX_STANDARD" to ToolchainVersions.CppStandard)))
            })
            configureArguments.add(jdk.map { "-DJAVA_HOME=${it.metadata.installationPath.asFile}" })
            configureArguments.add(output.map { "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${it.asFile}" })
            outputs.dir(output)
            if (sourceDeclarations.isNotEmpty()) outputs.dir(sourceRoot)
        }
        val resourceRoot = layout.buildDirectory.dir("generated/$libraryTaskName-resources")
        val packageLibraries = tasks.register<Sync>("${libraryTaskName}Resources") {
            dependsOn(compile)
            from(output) { include("*.dylib", "*.so", "*.dll", "*-LICENSE") }
            into(resourceRoot.map { it.dir("native/$platform") })
        }
        extensions.getByType<KotlinMultiplatformExtension>().sourceSets.matching { it.name == "jvmMain" }.configureEach {
            resources.srcDir(packageLibraries.map { resourceRoot.get() })
        }
        compile
    }
}
