@file:Suppress("UnstableApiUsage")

import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*


plugins {
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.kotlinx.benchmark")
    
}
dependeasy {
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
        }
    }

    droid {
        dependencies {
            api(project(":reaktor-io"))
            implementation("com.google.flatbuffers:flatbuffers-java:2.0.3")
        }
    }

    // JVM target — for server-side benchmarks and JVM flamechart profiling.
    // The pure-Kotlin V2 encoder/decoder compiles identically to the Android path,
    // but exercises HotSpot C2 instead of ART.
    server {
        dependencies {}
    }

    // JS (IR) target — for Node.js flamechart profiling.
    // Exercises V8 TurboFan JIT, BigInt Long handling, and ArrayBuffer byte ops.
    web {
        dependencies {}
    }

    darwin()

    // Benchmark tests (CrossPlatformBenchmark, MicroBench) exceed Mocha's 2s default.
    js {
        nodejs {
            testTask {
                useMocha { timeout = "300s" }
            }
        }
    }

    // Add -opt to the default debug test binaries so iOS benchmark numbers
    // reflect optimised compilation (close to what apps ship). Default Kotlin/Native
    // test binaries are built with -g and no optimisations — orders of magnitude slower.
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable> {
            freeCompilerArgs += listOf("-opt")
        }
    }

    // iOS-sim release benchmark executable for external profiling and scored
    // per-case encode/decode batches (configured through BENCH_* variables).
    // Build:  ./gradlew :reaktor-flexbuffer:linkBenchReleaseExecutableIosSimulatorArm64
    // Run:    ./reaktor-flexbuffer/build/bin/iosSimulatorArm64/benchReleaseExecutable/bench.kexe
    iosSimulatorArm64 {
        binaries {
            executable("bench", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE)) {
                entryPoint = "dev.shibasis.reaktor.flexbuffer.bench.iosBenchMain"
                freeCompilerArgs += listOf("-opt")
            }
        }
    }

    // Physical-device variant of the bench executable (wrap in a signed .app to run).
    // Build:  ./gradlew :reaktor-flexbuffer:linkBenchReleaseExecutableIosArm64
    iosArm64 {
        binaries {
            executable("bench", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE)) {
                entryPoint = "dev.shibasis.reaktor.flexbuffer.bench.iosBenchMain"
                freeCompilerArgs += listOf("-opt")
            }
        }
    }

    sourceSets {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-receiver-assertions",
                "-Xno-param-assertions"
            )
        }
    }

    targets.named<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>("jvm") {
        val mainCompilation = compilations.getByName("main")
        compilations.create("benchmark") {
            associateWith(mainCompilation)
            compileTaskProvider.configure {
                // Keep the thin JMH wrapper at 21 because kotlinx-benchmark's bytecode
                // generator currently runs on Gradle's Java 21 worker. Production code
                // remains Java 25 and the scored forks are pinned to Java 25 below.
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            }
            defaultSourceSet {
                kotlin.srcDir("src/jvmBenchmark/kotlin")
                dependencies {
                    implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.4.10")
                }
            }
        }
    }

}

benchmark {
    targets {
        register("jvmBenchmark") {
            this as kotlinx.benchmark.gradle.JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
    }
    configurations {
        named("main") {
            iterations = 7
            warmups = 5
            iterationTime = 750
            iterationTimeUnit = "ms"
            outputTimeUnit = "us"
            reportFormat = "json"
            include(".*FlexBufferJmhBenchmark.*")
            advanced("jvmForks", 3)
        }
    }
}

val jvmBenchmarkAllocationStats by tasks.registering(Exec::class) {
    group = "benchmark"
    description = "Run JVM JMH benchmarks with the JMH gc profiler for allocation stats"
    dependsOn("jvmBenchmarkBenchmarkJar")

    doFirst {
        val java25 = project.extensions.getByType<JavaToolchainService>()
            .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
            .get().executablePath.asFile.absolutePath
        val jarDir = layout.buildDirectory.dir("benchmarks/jvmBenchmark/jars").get().asFile
        val jmhJar = fileTree(jarDir) { include("*-JMH.jar") }.singleFile
        val report = layout.buildDirectory.file("reports/benchmarks/jvm-flexbuffer-jmh-gc.json").get().asFile
        report.parentFile.mkdirs()
        commandLine(
            java25,
            "-Xms1g",
            "-Xmx1g",
            "-jar",
            jmhJar.absolutePath,
            ".*FlexBufferJmhBenchmark.*",
            "-f", "3",
            "-wi", "5",
            "-i", "7",
            "-w", "750ms",
            "-r", "750ms",
            "-tu", "us",
            "-rf", "json",
            "-rff", report.absolutePath,
            "-prof", "gc"
        )
    }
}

tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    if (name == "jvmBenchmarkBenchmarkJar") {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":reaktor-compiler"))
}

extensions.configure<com.google.devtools.ksp.gradle.KspExtension> {
    arg("reaktor.flexcoder.registrar.package", "dev.shibasis.reaktor.flexbuffer.generated")
    arg("reaktor.flexcoder.registrar.object", "ReaktorFlexbufferCoders")
}

kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

android {
   defaults("dev.shibasis.reaktor.core")
}

// ── Flamechart benchmark tasks ─────────────────────────────────────────
// Run with: ./gradlew :reaktor-flexbuffer:jvmFlameChart
// Produces flamechart HTML in flamechart/output/

val jvmFlameChart by tasks.registering(JavaExec::class) {
    group = "benchmark"
    description = "Run FlexBuffer/JSON flamechart benchmark on JVM (attach async-profiler externally)"

    dependsOn("jvmMainClasses")

    // Match the jvmToolchain (Java 25) used to compile the main classes
    javaLauncher.set(
        project.extensions.getByType<JavaToolchainService>()
            .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    )

    mainClass.set("dev.shibasis.reaktor.flexbuffer.bench.JvmFlameChartKt")

    val jvmCompilation = kotlin.jvm().compilations["main"]
    classpath = files(
        jvmCompilation.output.allOutputs,
        jvmCompilation.runtimeDependencyFiles
    )

    // async-profiler agent — auto-detected from Homebrew or env var ASPROF_LIB
    val asprofLib = provider {
        val envLib = System.getenv("ASPROF_LIB")
        if (!envLib.isNullOrBlank() && file(envLib).exists()) return@provider envLib
        listOf(
            "/opt/homebrew/lib/libasyncProfiler.dylib",
            "/usr/local/lib/libasyncProfiler.dylib",
            "${System.getProperty("user.home")}/.local/lib/libasyncProfiler.dylib"
        ).firstOrNull { file(it).exists() }
    }

    val outputDir = project.file("flamechart/output")

    doFirst {
        outputDir.mkdirs()
        val lib = asprofLib.orNull
        if (lib != null && file(lib).exists()) {
            val htmlFile = File(outputDir, "jvm-flamechart.html")
            jvmArgs(
                "-agentpath:${lib}=start,event=cpu,flamegraph,file=${htmlFile.absolutePath}",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+DebugNonSafepoints"  // Better async-profiler frame accuracy
            )
            println("async-profiler agent attached → ${htmlFile.absolutePath}")
        } else {
            println("WARNING: async-profiler not found. Run benchmark without profiling.")
            println("  Install: brew install async-profiler")
            println("  Or set ASPROF_LIB=/path/to/libasyncProfiler.dylib")
        }
    }
}

// Per-phase profiler — captures separate CPU and alloc flamegraphs for each tier × payload
// Run with: ./gradlew :reaktor-flexbuffer:phaseProfile
val phaseProfile by tasks.registering(JavaExec::class) {
    group = "benchmark"
    description = "Run per-phase CPU + alloc profiling with async-profiler"

    dependsOn("jvmMainClasses")

    javaLauncher.set(
        project.extensions.getByType<JavaToolchainService>()
            .launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    )

    mainClass.set("dev.shibasis.reaktor.flexbuffer.bench.PhaseProfilerKt")

    val jvmCompilation = kotlin.jvm().compilations["main"]
    val asprofJar = file("/opt/homebrew/Cellar/async-profiler/4.3/libexec/async-profiler.jar")
    classpath = files(
        jvmCompilation.output.allOutputs,
        jvmCompilation.runtimeDependencyFiles,
        // Test runtime so we can reach the BenchmarkData fixtures
        kotlin.jvm().compilations["test"].output.allOutputs,
        kotlin.jvm().compilations["test"].runtimeDependencyFiles,
        asprofJar
    )

    jvmArgs(
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+DebugNonSafepoints"
    )

    val phaseOutDir = project.file("flamechart/output/phase").absolutePath
    environment("PHASE_OUT", phaseOutDir)

    doFirst {
        project.file(phaseOutDir).mkdirs()
    }
}

// Android flamechart — profiles the unit test JVM (same HotSpot, Android classpath)
// Usage: FLAMECHART=true ./gradlew :reaktor-flexbuffer:testDebugUnitTest --tests "*.FlexBuffersV2Benchmarks" --no-daemon --rerun
val androidFlameChart by tasks.registering {
    group = "benchmark"
    description = "Run Android unit test benchmarks with async-profiler attached"
}

tasks.withType<Test>().configureEach {
    exclude("**/*\$*")
    exclude("**/*Coder*")
    exclude("**/*ImplsKt*")
    testLogging {
        showStandardStreams = true
    }
}

// Inject async-profiler into the test JVM when FLAMECHART=true
if (System.getenv("FLAMECHART") == "true") {
    tasks.withType<Test> {
        val asprofLib = listOf(
            "/opt/homebrew/lib/libasyncProfiler.dylib",
            "/usr/local/lib/libasyncProfiler.dylib",
            "${System.getProperty("user.home")}/.local/lib/libasyncProfiler.dylib"
        ).firstOrNull { file(it).exists() }

        if (asprofLib != null) {
            val outputFile = project.file("flamechart/output/android-flamechart.html")
            project.file("flamechart/output").mkdirs()
            jvmArgs(
                "-agentpath:${asprofLib}=start,event=cpu,flamegraph,file=${outputFile.absolutePath}",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+DebugNonSafepoints"
            )
        }
    }
}
