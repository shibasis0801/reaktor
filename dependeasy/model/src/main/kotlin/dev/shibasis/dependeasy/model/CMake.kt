package dev.shibasis.dependeasy.model

object CMake {
    const val All = "all"
    fun tests(directory: String, results: String): List<String> =
        listOf("--test-dir", directory, "--output-on-failure", "--no-tests=error", "--output-junit", results)

    fun toolchain(minimum: String, standard: Int, configuration: String): List<String> = listOf(
        "-DDEPE_CMAKE_MINIMUM=$minimum", "-DCMAKE_CXX_STANDARD=$standard", "-DCMAKE_CXX_EXTENSIONS=OFF",
        "-DCMAKE_CXX_STANDARD_REQUIRED=ON", "-DCMAKE_BUILD_TYPE=$configuration", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON")

    fun configure(spec: KernelSpec.CMakeBuild): List<String> {
        val native = CMakeBuildKernel.nativeConfiguration(spec)
        return listOf("-DCMAKE_CXX_SCAN_FOR_MODULES=OFF", "-C", native, "-DDEPE_NATIVE_CONTRACT=$native",
            "-S", spec.source, "-B", spec.output,
            "-G", spec.generator, "-DCMAKE_MAKE_PROGRAM=${spec.ninjaExecutable}") + spec.arguments
    }

    fun build(spec: KernelSpec.CMakeBuild): List<String> = listOf("--build", spec.output,
        "--config", spec.configuration, "--parallel", spec.parallelism.toString(), "--target", spec.target)
}
