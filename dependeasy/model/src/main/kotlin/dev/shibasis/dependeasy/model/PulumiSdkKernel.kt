package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.clearChildrenSafely
import java.io.File
import kotlinx.serialization.json.*

object Pulumi {
    fun generateSdk(spec: KernelSpec.PulumiSdk): List<String> = listOf("package", "gen-sdk", spec.provider,
        "--language", "java", "--version", spec.version, "--server", spec.server,
        "--out", spec.output.paths.single(), "--non-interactive")
}

object PulumiSdkKernel {
    fun plan(spec: KernelSpec.PulumiSdk): ProcessExecution {
        require(spec.provider.isNotBlank() && spec.version.isNotBlank() && spec.tool.version.isNotBlank())
        require(spec.output.paths.size == 1)
        return ProcessExecution(".", spec.executable, Pulumi.generateSdk(spec), listOf("PATH"), spec.tool, emptyList(), spec.output.paths)
    }

    fun prepare(spec: KernelSpec.PulumiSdk) {
        plan(spec)
        File(spec.output.paths.single()).clearChildrenSafely()
    }

    fun environment(spec: KernelSpec.PulumiSdk, path: String): Map<String, String> =
        mapOf("PATH" to (File(spec.executable).parent + File.pathSeparator + path))

    fun resources(spec: KernelSpec.PulumiSdk): Map<String, String> {
        val prefix = "resources/com/pulumi/${spec.provider}/"
        val metadata = buildJsonObject {
            put("resource", true); put("name", spec.provider); put("server", spec.server); put("version", spec.version)
        }
        return mapOf(prefix + "version.txt" to spec.version, prefix + "plugin.json" to metadata.toString())
    }
}
