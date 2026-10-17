package dev.shibasis.dependeasy.desktop

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.model.NativeContract
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

class NativeDesktopLibrary internal constructor(private val project: Project, private val name: String) {
    var source: Any = "src/jvmMain/cpp"
    var target: String = name
    private val defines = linkedMapOf<String, String>()
    fun define(name: String, value: Any) { defines[name] = value.toString() }

    internal fun register() = with(project) {
        val os = providers.systemProperty("os.name").get()
        val arch = providers.systemProperty("os.arch").get()
        val platform = (if (os.startsWith("Mac")) "macos-" else if (os.startsWith("Windows")) "windows-" else "linux-") +
            (if (arch in setOf("aarch64", "arm64")) "arm64" else "x64")
        val libraryTaskName = this@NativeDesktopLibrary.name
        val output = layout.buildDirectory.dir("libs/$libraryTaskName/$platform")
        val jdk = extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
        }
        val compile = tasks.register<KotlinCMakeTask>(libraryTaskName) {
            group = "dependeasy"
            sourceDirectory.set(file(source))
            sourceFiles.from(fileTree(file(source)))
            buildDirectory.set(layout.buildDirectory.dir("native/$libraryTaskName/$platform"))
            generator.set("Ninja")
            buildTarget.set(target)
            val libraryName = target
            val declarations = defines.toMap()
            nativeContract.set(dev.shibasis.dependeasy.tasks.CmakePlatform.Host().settings(project).map { settings ->
                BuildModelReader.json.encodeToString(NativeContract(library = libraryName, platform = platform,
                    settings = settings + declarations + mapOf("CMAKE_BUILD_TYPE" to "Release", "CMAKE_CXX_STANDARD" to ToolchainVersions.CppStandard, "CMAKE_OBJCXX_STANDARD" to ToolchainVersions.CppStandard)))
            })
            configureArguments.add(jdk.map { "-DJAVA_HOME=${it.metadata.installationPath.asFile}" })
            configureArguments.add(output.map { "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${it.asFile}" })
            outputs.dir(output)
        }
        val resourceRoot = layout.buildDirectory.dir("generated/$libraryTaskName-resources")
        val packageLibraries = tasks.register<Sync>("${libraryTaskName}Resources") {
            dependsOn(compile)
            from(output) { include("*.dylib", "*.so", "*.dll", "*-LICENSE") }
            into(resourceRoot.map { it.dir("native/$platform") })
        }
        extensions.getByType<KotlinMultiplatformExtension>().sourceSets.named("jvmMain") {
            resources.srcDir(packageLibraries.map { resourceRoot.get() })
        }
        compile
    }
}
