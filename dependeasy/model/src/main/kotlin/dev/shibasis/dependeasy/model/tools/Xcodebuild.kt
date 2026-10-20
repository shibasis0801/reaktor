package dev.shibasis.dependeasy.model.tools

import dev.shibasis.dependeasy.model.*

object Xcodebuild {
    fun cacheSettings(host: AppleHostContract): Map<String, String> = listOf(
        "COMPILATION_CACHE_ENABLE_CACHING", "ENABLE_GENERIC_TASK_CACHING",
    ).associateWith { if (host.compilationCaching) "YES" else "NO" }

    fun build(spec: KernelSpec.ReleasePlan, configuration: String, derived: String, archive: String? = null): ReleaseCommand {
        val application = spec.host.targets.single { it.name == spec.host.applicationTarget }
        val arguments = AppleGradle.arguments(spec.host, spec.versionName, spec.buildNumber.toString(), configuration == "Release").joinToString(" ")
        val environment = mapOf("${application.settingsPrefix}_GRADLE_ARGS" to arguments, "LLVM_CAS_MAX_MAPPING_SIZE" to "134217728")
        val tokens = listOf("xcodebuild", "-jobs", "3", "-project", "${spec.xcodeDirectory}/${spec.xcodeProject}", "-scheme", spec.scheme,
            "-configuration", configuration, "-destination", "generic/platform=iOS", "-derivedDataPath", derived,
            "MARKETING_VERSION=${spec.versionName}", "CURRENT_PROJECT_VERSION=${spec.buildNumber}") +
            cacheSettings(spec.host).map { (key, value) -> "$key=$value" } + "${application.settingsPrefix}_GRADLE_ARGS=$arguments"
        return ReleaseCommand(".", tokens + if (archive == null) listOf("build") else listOf("-archivePath", archive, "archive"), environment)
    }

    fun export(archive: String, destination: String, options: String): ReleaseCommand = ReleaseCommand(".", listOf(
        "xcodebuild", "-exportArchive", "-archivePath", archive, "-exportPath", destination, "-exportOptionsPlist", options))
}
