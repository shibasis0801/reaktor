@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.LintOptions
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*


plugins {
    id("dev.shibasis.dependeasy.library")
    id("dev.shibasis.publisheasy")
}

val Name = "FlatInvokerCore"


fun darwinCmake(sdk: String): Exec {
    val prefix = sdk
    return tasks.create<Exec>("${prefix}CMake") {
        group = "reaktor"
        workingDir = file("cpp")
        commandLine = listOf("bash", "-c", """
            mkdir -p build &&
            cd build &&   
            rm -rf $prefix &&
            cmake -B $prefix -G Xcode \
                -DCMAKE_BUILD_TYPE=Release \
                -Dsdk=${sdk} .. \
                -DiOS=true &&
            cmake --build $prefix --config Release
        """.trimIndent()
        )
    }
}
val iosCmake = darwinCmake("iphoneos")
val iosSimulatorCmake = darwinCmake("iphonesimulator")

tasks.named("build") {
    dependsOn(iosCmake, iosSimulatorCmake)
}

kotlin {
    common {
        dependencies = {
            api(project(":flatbuffers-kotlin"))
            api(project(":reaktor-core"))
        }
    }

    droid {
        dependencies = {
            api(project(":reaktor-io"))
            implementation("com.google.flatbuffers:flatbuffers-java:2.0.3")
        }
    }

    darwin {
        cinterops = {
            val reaktor by creating {
                extraOpts("-Xsource-compiler-option", "-std=c++20")
                extraOpts("-Xsource-compiler-option", "-stdlib=libc++")
                packageName("dev.shibasis.flatinvoker.native")
                // todo create def file if not present
                defFile(file("cpp/bindings.def"))
                includeDirs("cpp")
                headers("cpp/common/CBase.h", "cpp/darwin/Reaktor.h", "cpp/common/Flex.h")
            }
        }

        targets = {
            println("DarwinTarget: $name")
            val code = if (name.lowercase().contains("simulator")) "iphonesimulator" else "iphoneos"
            binaries.all {
                freeCompilerArgs += listOf("-linker-option", file("cpp/build/$code/Release-$code/lib${Name}.a").absolutePath)
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

}

dependencies {
    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
}

android {
   defaults("dev.shibasis.flatinvoker.core", file("cpp/CMakeLists.txt"), Name)
}