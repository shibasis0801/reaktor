package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.model.*
import kotlinx.serialization.json.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

internal fun AppleHostContract.cacheSettings(): Map<String, String> = listOf(
    "COMPILATION_CACHE_ENABLE_CACHING", "ENABLE_GENERIC_TASK_CACHING",
).associateWith { if (compilationCaching) "YES" else "NO" }

internal fun xcodeHostFiles(declaration: String, root: String, wrapper: String, task: String,
                            version: String, number: Int, signing: String): Map<String, String> {
    val host = BuildModelReader.json.decodeFromString<AppleHostContract>(declaration)
    fun value(text: String): String {
        require(!text.contains('\n') && !text.contains('\r')) { "Invalid Xcode setting" }
        return text
    }
    val versionArgs = listOf(host.versionOption to version, host.numberOption to number.toString()).filter { it.first.isNotBlank() }.map { "-P${it.first}=${it.second}" }
    val files = linkedMapOf<String, String>()
    host.targets.forEachIndexed { index, target ->
        listOf("Debug", "Release").forEach { configuration ->
            val prefix = if (index == 0) "BESTBUDS" else "BESTBUDS_NSE"
            val settings = linkedMapOf(
                "IPHONEOS_DEPLOYMENT_TARGET" to host.deploymentTarget, "SWIFT_VERSION" to host.swiftVersion,
                "SDKROOT" to "iphoneos", "CLANG_CXX_LANGUAGE_STANDARD" to "c++${dev.shibasis.dependeasy.toolchain.ToolchainVersions.CppStandard}",
                "APS_ENVIRONMENT" to "\$(BESTBUDS_APS_ENVIRONMENT)", "SUPPORTED_PLATFORMS" to "iphoneos iphonesimulator",
                "ENABLE_USER_SCRIPT_SANDBOXING" to "NO", "PRODUCT_BUNDLE_IDENTIFIER" to target.bundleId,
                "DEVELOPMENT_TEAM" to host.team, "MARKETING_VERSION" to version, "CURRENT_PROJECT_VERSION" to number.toString(),
                "FRAMEWORK_SEARCH_PATHS" to "\$(inherited) \"\$(SRCROOT)/${target.frameworkDirectory}/\$(CONFIGURATION)/\$(SDK_NAME)\"",
                "OTHER_LDFLAGS" to "\$(inherited) -framework ${target.framework} ${target.linkFlags.joinToString(" ")}",
                "REAKTOR_ROOT" to "\$(SRCROOT)/$root", "REAKTOR_GRADLEW" to "\$(REAKTOR_ROOT)/$wrapper",
                "REAKTOR_KOTLIN_TASK" to task, "CODE_SIGN_STYLE" to "\$(${prefix}_CODE_SIGN_STYLE)",
                "CODE_SIGN_IDENTITY" to "\$(${prefix}_CODE_SIGN_IDENTITY)",
                "PROVISIONING_PROFILE_SPECIFIER" to "\$(${prefix}_PROVISIONING_PROFILE_SPECIFIER)",
                "BESTBUDS_GRADLE_ARGS" to (versionArgs + if (configuration == "Release" && host.developmentToolsOption.isNotBlank()) listOf("-P${host.developmentToolsOption}=false") else emptyList()).joinToString(" "),
            )
            settings.putAll(host.cacheSettings())
            if (index == 0) settings["UNLOCALIZED_RESOURCES_FOLDER_PATH"] = "${target.name}.app"
            target.backgroundTasks.forEachIndexed { position, id -> settings["REAKTOR_BG_TASK_$position"] = id }
            files["${target.name}-$configuration.xcconfig"] = buildString {
                append("${prefix}_CODE_SIGN_STYLE = Automatic\n${prefix}_CODE_SIGN_IDENTITY = Apple Development\n")
                append("BESTBUDS_APS_ENVIRONMENT = ${if (configuration == "Release") "production" else "development"}\n")
                append("#include? \"${value(signing)}\"\n")
                settings.forEach { (key, setting) -> append("$key = ${value(setting)}".trimEnd() + "\n") }
            }
        }
    }
    files["Bootstrap.swift"] = buildString {
        append("import ${host.targets.first().framework}\n\nenum ReaktorBootstrap {\n    static func launch() {\n")
        host.targets.first().adapters.forEach { append("        $it\n") }
        if (host.entryPoint.isNotBlank()) append("        ${value(host.entryPoint)}\n")
        append("    }\n}\n")
    }
    return files
}

@CacheableTask
abstract class XcodeHostTask : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.XcodeHost(BuildModelReader.json.decodeFromString(declaration.get()), false, listOf(destination.get().asFile.absolutePath))
    @get:Input abstract val declaration: Property<String>
    @get:Input abstract val rootPath: Property<String>
    @get:Input abstract val wrapper: Property<String>
    @get:Input abstract val kotlinTask: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<Int>
    @get:Input abstract val signingInclude: Property<String>
    @get:OutputDirectory abstract val destination: DirectoryProperty
    @get:OutputFile abstract val contract: RegularFileProperty
    init { wrapper.convention("gradlew"); signingInclude.convention("") }
    @TaskAction fun write() {
        val output = destination.get().asFile.apply { mkdirs() }
        xcodeHostFiles(declaration.get(), rootPath.get(), wrapper.get(), kotlinTask.get(), versionName.get(), buildNumber.get(), signingInclude.get())
            .forEach { (name, text) -> output.resolve(name).writeText(text) }
        contract.get().asFile.apply { parentFile.mkdirs(); writeText(declaration.get() + "\n") }
    }
}

@DisableCachingByDefault(because = "Checks tracked Xcode host settings for drift")
abstract class VerifyXcodeHost : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.XcodeHost(BuildModelReader.json.decodeFromString(declaration.get()), true, listOf(destination.get().asFile.absolutePath))
    @get:Internal override val kernelEffect: Effect get() = Effect.Verify
    @get:Input abstract val declaration: Property<String>
    @get:Input abstract val rootPath: Property<String>
    @get:Input abstract val wrapper: Property<String>
    @get:Input abstract val kotlinTask: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<Int>
    @get:Input abstract val signingInclude: Property<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val destination: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val resolvedPackages: RegularFileProperty
    @TaskAction fun verify() {
        val expected = xcodeHostFiles(declaration.get(), rootPath.get(), wrapper.get(), kotlinTask.get(), versionName.get(), buildNumber.get(), signingInclude.get())
        val drift = expected.keys.filter { name -> destination.get().asFile.resolve(name).let { !it.isFile || it.readText() != expected.getValue(name) } }
        require(drift.isEmpty()) { "Xcode host settings drifted: ${drift.joinToString()}. Run generateXcodeHost." }
        val pins = Json.parseToJsonElement(resolvedPackages.get().asFile.readText()).jsonObject.getValue("pins").jsonArray
            .associate { it.jsonObject.getValue("identity").jsonPrimitive.content to it.jsonObject.getValue("state").jsonObject["version"]?.jsonPrimitive?.content }
        val declared = BuildModelReader.json.decodeFromString<AppleHostContract>(declaration.get()).swiftPackages
        declared.forEach { (identity, version) -> require(pins[identity] == version) { "SwiftPM pin drift: $identity expected $version, resolved ${pins[identity]}" } }
        require("googlesignin-ios" !in pins || "googlesignin-ios" in declared) { "Stale GoogleSignIn SwiftPM pin; the host uses AppAuth." }
    }
}
