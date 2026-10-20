package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.model.tools.AppleGradle
import dev.shibasis.dependeasy.model.tools.Xcodebuild

object AppleHostKernel {
    fun files(spec: KernelSpec.XcodeHost): Map<String, String> {
        val host = spec.host
        val root = spec.rootPath
        val wrapper = spec.wrapper
        val task = spec.kotlinTask
        val signing = spec.signingInclude
        fun value(text: String): String {
            require(!text.contains('\n') && !text.contains('\r')) { "Invalid Xcode setting" }
            return text
        }
        val versions = java.io.File(signing).resolveSibling("Version.local.xcconfig").invariantSeparatorsPath
        val application = host.targets.single { it.name == host.applicationTarget }
        val files = linkedMapOf<String, String>()
        host.targets.forEach { target ->
            listOf("Debug", "Release").forEach { configuration ->
                val prefix = target.settingsPrefix
                val settings = linkedMapOf(
                    "IPHONEOS_DEPLOYMENT_TARGET" to host.deploymentTarget, "SWIFT_VERSION" to host.swiftVersion,
                    "SDKROOT" to "iphoneos", "CLANG_CXX_LANGUAGE_STANDARD" to "c++${spec.cppStandard}",
                    "APS_ENVIRONMENT" to "\$(${application.settingsPrefix}_APS_ENVIRONMENT)", "SUPPORTED_PLATFORMS" to "iphoneos iphonesimulator",
                    "ENABLE_USER_SCRIPT_SANDBOXING" to "NO", "PRODUCT_BUNDLE_IDENTIFIER" to target.bundleId,
                    "DEVELOPMENT_TEAM" to host.team,
                    "FRAMEWORK_SEARCH_PATHS" to "\$(inherited) \"\$(SRCROOT)/${target.frameworkDirectory}/\$(CONFIGURATION)/\$(SDK_NAME)\"",
                    "OTHER_LDFLAGS" to "\$(inherited) -framework ${target.framework} ${target.linkFlags.joinToString(" ")}",
                    "REAKTOR_ROOT" to "\$(SRCROOT)/$root", "REAKTOR_GRADLEW" to "\$(REAKTOR_ROOT)/$wrapper",
                    "REAKTOR_KOTLIN_TASK" to task, "CODE_SIGN_STYLE" to "\$(${prefix}_CODE_SIGN_STYLE)",
                    "CODE_SIGN_IDENTITY" to "\$(${prefix}_CODE_SIGN_IDENTITY)",
                    "PROVISIONING_PROFILE_SPECIFIER" to "\$(${prefix}_PROVISIONING_PROFILE_SPECIFIER)",
                    "${application.settingsPrefix}_GRADLE_ARGS" to AppleGradle.arguments(host, "\$(MARKETING_VERSION)", "\$(CURRENT_PROJECT_VERSION)", configuration == "Release").joinToString(" "),
                )
                settings.putAll(Xcodebuild.cacheSettings(host))
                if (target.name == host.applicationTarget) settings["UNLOCALIZED_RESOURCES_FOLDER_PATH"] = "${target.name}.app"
                target.backgroundTasks.forEachIndexed { position, id -> settings["REAKTOR_BG_TASK_$position"] = id }
                files["${target.name}-$configuration.xcconfig"] = buildString {
                    append("${prefix}_CODE_SIGN_STYLE = Automatic\n${prefix}_CODE_SIGN_IDENTITY = Apple Development\n")
                    append("${application.settingsPrefix}_APS_ENVIRONMENT = ${if (configuration == "Release") "production" else "development"}\n")
                    append("#include? \"${value(signing)}\"\n")
                    append("#include? \"${value(versions)}\"\n")
                    settings.forEach { (key, setting) -> append("$key = ${value(setting)}".trimEnd() + "\n") }
                }
            }
        }
        files["Bootstrap.swift"] = buildString {
            append("import ${application.framework}\n\nenum ReaktorBootstrap {\n    static func launch() {\n")
            application.adapters.forEach { append("        $it\n") }
            if (host.entryPoint.isNotBlank()) append("        ${value(host.entryPoint)}\n")
            append("    }\n}\n")
        }
        return files
    }

    fun versionFile(spec: KernelSpec.XcodeHost): String {
        require(!spec.versionName.contains('\n') && !spec.versionName.contains('\r')) { "Invalid Xcode version" }
        return "MARKETING_VERSION = ${spec.versionName}\nCURRENT_PROJECT_VERSION = ${spec.buildNumber}\n"
    }

    fun verify(spec: KernelSpec.XcodeHost, actual: Map<String, String>, pins: Map<String, String?>) {
        val expected = files(spec)
        val drift = expected.keys.filter { actual[it] != expected.getValue(it) }
        require(drift.isEmpty()) { "Xcode host settings drifted: ${drift.joinToString()}. Run generateXcodeHost." }
        spec.host.swiftPackages.forEach { (identity, version) ->
            require(pins[identity] == version) { "SwiftPM pin drift: $identity expected $version, resolved ${pins[identity]}" }
        }
        require("googlesignin-ios" !in pins || "googlesignin-ios" in spec.host.swiftPackages) { "Stale GoogleSignIn SwiftPM pin; the host uses AppAuth." }
    }
}
