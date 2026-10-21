import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.configuration.ReaktorTests
import dev.shibasis.dependeasy.native.*
import dev.shibasis.dependeasy.native.NativePlatform
import dev.shibasis.dependeasy.handles.Tools
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.model.Suite
import dev.shibasis.dependeasy.model.TestEnvironment
import dev.shibasis.dependeasy.model.FileSet
import kotlin.time.Duration.Companion.minutes
import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.flexbuffer", targets = Targets.All, jvmNullChecks = false) {
    testProfile("flamechart/output/android-flamechart.html")
    val jmh by jvmBenchmarks(sources = FileSet(listOf("reaktor-flexbuffer/src/jvmBenchmark/kotlin/dev/shibasis/reaktor/flexbuffer/bench/FlexBufferJmhBenchmark.kt")))
    val unit by tests(Suite.Jvm, environment = ReaktorTests.Benchmarks)
    val reference by cppReference(executable = "flexbuffer_bench", property = "flexbuffer.reference",
        headers = mapOf(Tools.Flatbuffers to "FLATBUFFERS_INCLUDE_DIR"))
    val bench by appleBenchmarks()
    val jvmFlameChart by cpuProfile(
        sources = FileSet(listOf("reaktor-flexbuffer/src/jvmMain/kotlin/dev/shibasis/reaktor/flexbuffer/bench/JvmFlameChart.kt")),
        output = "flamechart/output/jvm-flamechart.html")
    val phaseProfile by apiProfile(
        sources = FileSet(listOf("reaktor-flexbuffer/src/jvmMain/kotlin/dev/shibasis/reaktor/flexbuffer/bench/PhaseProfiler.kt")),
        output = "flamechart/output/phase", outputEnvironment = "PHASE_OUT")
    val androidUnit by tests(Suite.AndroidUnit, environment = ReaktorTests.Benchmarks)
    val node by tests(Suite.JavaScript, environment = TestEnvironment(caseTimeoutMillis = 5.minutes.inWholeMilliseconds))
    native(name = "ReaktorFlexbuffer", platforms = setOf(NativePlatform.Android, NativePlatform.Darwin, NativePlatform.Host),
        abis = listOf(NativeAbi.X86, NativeAbi.Arm64, NativeAbi.X86_64)) {
        links(Native.Flatbuffers, Native.Foundation)
        includeDirs("src/commonMain/cpp", "src/androidMain/cpp", "src/iosMain/cpp")
    }
    appleInterop(packageName = "dev.shibasis.reaktor.native", headers = listOf("src/iosMain/cpp/darwin/Reaktor.h"))
    dependencies {
        processors(Reaktor.Compiler, options = mapOf(
            Reaktor.CompilerOptions.FlexCoderRegistrarPackage to "dev.shibasis.reaktor.flexbuffer.generated",
            Reaktor.CompilerOptions.FlexCoderRegistrarObject to "ReaktorFlexbufferCoders",
        ))
        common { api(projects.reaktorCore) }
        android {
            api(projects.reaktorIo)
            implementation(Versions.Native.Flatbuffers)
        }
    }
}
