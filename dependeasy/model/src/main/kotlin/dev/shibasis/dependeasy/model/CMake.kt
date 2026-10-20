package dev.shibasis.dependeasy.model

object CMake {
    const val All = "all"
    fun tests(directory: String, results: String): List<String> =
        listOf("--test-dir", directory, "--output-on-failure", "--no-tests=error", "--output-junit", results)

    fun configure(spec: KernelSpec.CMakeBuild): List<String> {
        val native = CMakeBuildKernel.nativeConfiguration(spec)
        return listOf("-C", spec.contract, "-C", native, "-DDEPE_NATIVE_CONTRACT=$native",
            "-DDEPE_TOOLCHAIN_CONTRACT=${spec.contract}", "-S", spec.source, "-B", spec.output,
            "-G", spec.generator, "-DCMAKE_MAKE_PROGRAM=${spec.ninjaExecutable}") + spec.arguments
    }

    fun build(spec: KernelSpec.CMakeBuild): List<String> = listOf("--build", spec.output,
        "--config", spec.configuration, "--parallel", spec.parallelism.toString(), "--target", spec.target)
}
