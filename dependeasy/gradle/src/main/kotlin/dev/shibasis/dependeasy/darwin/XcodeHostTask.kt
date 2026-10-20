package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.json.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@CacheableTask
abstract class XcodeHostTask : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec.XcodeHost get() = KernelSpec.XcodeHost(
        BuildModelReader.json.decodeFromString(declaration.get()), false,
        listOf(destination.get().asFile.absolutePath, contract.get().asFile.absolutePath, contract.get().asFile.resolveSibling("Version.local.xcconfig").absolutePath),
        rootPath.get(), wrapper.get(), kotlinTask.get(), signingInclude.get(), cppStandard.get(), versionName.get(), buildNumber.get())
    @get:Input abstract val declaration: Property<String>
    @get:Input abstract val rootPath: Property<String>
    @get:Input abstract val wrapper: Property<String>
    @get:Input abstract val kotlinTask: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<Int>
    @get:Input abstract val signingInclude: Property<String>
    @get:Input abstract val cppStandard: Property<String>
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:OutputFile abstract val contract: RegularFileProperty
    init {
        wrapper.convention("gradlew"); signingInclude.convention("")
        cppStandard.convention(dev.shibasis.dependeasy.toolchain.ToolchainVersions.CppStandard)
        outputs.file(contract.locationOnly.map { it.asFile.resolveSibling("Version.local.xcconfig") })
    }
    @TaskAction fun write() {
        val spec = kernelSpec
        val files = AppleHostKernel.files(spec)
        val versions = AppleHostKernel.versionFile(spec)
        val output = destination.get().asFile.apply { mkdirs() }
        files.forEach { (name, text) -> output.resolve(name).writeText(text) }
        contract.get().asFile.apply {
            parentFile.mkdirs(); writeText(declaration.get() + "\n")
            resolveSibling("Version.local.xcconfig").writeText(versions)
        }
    }
}

@DisableCachingByDefault(because = "Checks tracked Xcode host settings for drift")
abstract class VerifyXcodeHost : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec.XcodeHost get() = KernelSpec.XcodeHost(
        BuildModelReader.json.decodeFromString(declaration.get()), true,
        listOf(destination.get().asFile.absolutePath, resolvedPackages.get().asFile.absolutePath),
        rootPath.get(), wrapper.get(), kotlinTask.get(), signingInclude.get(), cppStandard.get(), versionName.get(), buildNumber.get())
    @get:Internal override val kernelEffect: Effect get() = Effect.Verify
    @get:Input abstract val declaration: Property<String>
    @get:Input abstract val rootPath: Property<String>
    @get:Input abstract val wrapper: Property<String>
    @get:Input abstract val kotlinTask: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<Int>
    @get:Input abstract val signingInclude: Property<String>
    @get:Input abstract val cppStandard: Property<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val destination: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val resolvedPackages: RegularFileProperty
    init { cppStandard.convention(dev.shibasis.dependeasy.toolchain.ToolchainVersions.CppStandard) }
    @TaskAction fun verify() {
        val spec = kernelSpec
        val actual = AppleHostKernel.files(spec).keys.mapNotNull { name ->
            destination.get().asFile.resolve(name).takeIf { it.isFile }?.let { name to it.readText() }
        }.toMap()
        val pins = Json.parseToJsonElement(resolvedPackages.get().asFile.readText()).jsonObject.getValue("pins").jsonArray
            .associate { it.jsonObject.getValue("identity").jsonPrimitive.content to it.jsonObject.getValue("state").jsonObject["version"]?.jsonPrimitive?.content }
        AppleHostKernel.verify(spec, actual, pins)
    }
}
