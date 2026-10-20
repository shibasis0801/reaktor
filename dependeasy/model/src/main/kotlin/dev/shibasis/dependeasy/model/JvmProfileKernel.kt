package dev.shibasis.dependeasy.model

import java.io.File

object AsyncProfiler {
    fun cpuArguments(library: String, output: String): List<String> {
        check(library.isNotEmpty()) { "async-profiler library unavailable; set ASPROF_LIB" }
        return listOf("-agentpath:$library=start,event=cpu,flamegraph,file=$output", "-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints")
    }

    fun versionArguments(library: String) = listOf("-agentpath:$library=version", "-version")

    fun requireVersion(expected: ToolRef, reported: String) {
        require(reported.trim() == expected.version) { "${expected.name} requires ${expected.version}; found ${reported.trim()}" }
    }
}

object JvmProfileKernel {
    fun validate(spec: KernelSpec.Profile) {
        require(spec.output.paths.size == 1)
        check(spec.library.paths.singleOrNull()?.isNotBlank() == true) { "async-profiler library unavailable; set ASPROF_LIB" }
        if (spec.outputEnvironment != null) {
            require(spec.outputEnvironment.isNotBlank())
            check(spec.api.paths.singleOrNull()?.isNotBlank() == true) { "async-profiler API unavailable; set ASPROF_JAR" }
        }
        require(spec.java.version.isNotBlank() && spec.profiler.version.isNotBlank())
    }

    fun environment(spec: KernelSpec.Profile): Map<String, String> {
        validate(spec)
        return spec.outputEnvironment?.let { mapOf(it to spec.output.paths.single(), "ASPROF_LIB" to spec.library.paths.single()) }.orEmpty()
    }

    fun prepare(spec: KernelSpec.Profile) {
        validate(spec)
        val output = File(spec.output.paths.single())
        if (spec.outputEnvironment == null) output.parentFile.mkdirs() else output.mkdirs()
    }
}
