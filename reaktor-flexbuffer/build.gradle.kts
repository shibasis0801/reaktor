import dev.shibasis.dependeasy.Versions
plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    nativeLibrary { includeDirs(rootProject.file(".github_modules/flatbuffers/include")) }
    module("dev.shibasis.reaktor.flexbuffer") {
        common { dependencies { api(project(":reaktor-core")) } }
        android {
            dependencies {
                api(project(":reaktor-io"))
                implementation(Versions.Native.Flatbuffers)
            }
        }
        jvm {}; web {}; apple {}
    }
    kotlinProcessor(project(":reaktor-compiler")) {
        option("reaktor.flexcoder.registrar.package", "dev.shibasis.reaktor.flexbuffer.generated")
        option("reaktor.flexcoder.registrar.object", "ReaktorFlexbufferCoders")
    }
    benchmarks {
        cpp("flexbuffer_bench", property = "flexbuffer.reference") {
            sourceLibrary("flatbuffers", includeVariable = "FLATBUFFERS_INCLUDE_DIR")
        }
        jvm(".*FlexBufferJmhBenchmark.*")
        apple("dev.shibasis.reaktor.flexbuffer.bench.iosBenchMain")
        node()
        profile("jvmFlameChart", "dev.shibasis.reaktor.flexbuffer.bench.JvmFlameChartKt", "flamechart/output") {
            cpuAgent("jvm-flamechart.html")
        }
        profile("phaseProfile", "dev.shibasis.reaktor.flexbuffer.bench.PhaseProfilerKt", "flamechart/output/phase") {
            fixtures(); profilerApi(); environment("PHASE_OUT")
        }
        testProfile("flamechart/output/android-flamechart.html")
    }


    tasks.withType<Test>().configureEach {
        exclude("**/*\$*", "**/*Coder*", "**/*ImplsKt*")
        testLogging.showStandardStreams = true
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions.freeCompilerArgs.addAll("-Xno-call-assertions", "-Xno-receiver-assertions", "-Xno-param-assertions")
    }
}
