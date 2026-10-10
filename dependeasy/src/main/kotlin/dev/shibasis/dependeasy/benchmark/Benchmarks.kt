package dev.shibasis.dependeasy.benchmark

import org.gradle.api.Project

/** Workloads are declared here; compiler, runner and profiler wiring stays in the kernels. */
class Benchmarks internal constructor(private val project: Project) {
    fun jvm(pattern: String, configure: JvmBenchmark.() -> Unit = {}) =
        JvmBenchmark(pattern).apply(configure).also { JvmBenchmarkKernel(project, it).install() }

    fun apple(entryPoint: String, name: String = "bench") = AppleBenchmarkKernel(project).install(entryPoint, name)
    fun node(timeout: String = "300s") = NodeBenchmarkKernel(project).install(timeout)
    fun testProfile(output: String) = TestProfileKernel(project).install(output)
    fun cpp(name: String, property: String = "$name.executable",
            configure: dev.shibasis.dependeasy.native.CMakeBuild.() -> Unit = {}) =
        CppBenchmarkKernel(project).install(name, property, configure)

    fun profile(name: String, mainClass: String, output: String, configure: Profile.() -> Unit = {}) =
        Profile(name, mainClass, output).apply(configure).also { ProfileKernel(project, it).install() }
}

class JvmBenchmark internal constructor(val pattern: String) {
    var forks = 3
    var warmups = 5
    var iterations = 7
    var iterationMillis = 750L
    var outputUnit = "us"
    var allocationTask = "jvmBenchmarkAllocationStats"
}

class Profile internal constructor(val name: String, val mainClass: String, val output: String) {
    internal var fixtures = false
    internal var api = false
    internal var flamegraph: String? = null
    internal val variables = linkedMapOf<String, String>()
    fun fixtures() { fixtures = true }
    fun profilerApi() { api = true }
    fun cpuAgent(file: String) { flamegraph = file }
    fun environment(name: String, value: String = output) { variables[name] = value }
}
