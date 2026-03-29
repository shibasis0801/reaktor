@file:Suppress("UnstableApiUsage")

import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*


plugins {
    id("dev.shibasis.dependeasy.library")
    
}
dependeasy {
}

kotlin {
    common {
        dependencies {
            api(project(":flatbuffers-kotlin"))
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
    sourceSets {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-receiver-assertions",
                "-Xno-param-assertions"
            )
        }
    }

}

dependencies {
    add("kspCommonMainMetadata", project(":reaktor-compiler"))
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

// Android flamechart — profiles the unit test JVM (same HotSpot, Android classpath)
// Usage: FLAMECHART=true ./gradlew :reaktor-flexbuffer:testDebugUnitTest --tests "*.FlexBuffersV2Benchmarks" --no-daemon --rerun
val androidFlameChart by tasks.registering {
    group = "benchmark"
    description = "Run Android unit test benchmarks with async-profiler attached"
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
