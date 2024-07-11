@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.LintOptions
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING


plugins {
    id("dev.shibasis.dependeasy.library")
}

val Name = "FlatInvokerCore"

task<Exec>("darwinCmake") {
    group = "reaktor"
    // environment variable for react location
    commandLine =  listOf("bash", "-c", """
        cd cpp &&
        rm -rf build &&
        cmake -B build -G Xcode &&
        cmake --build build --config Release
    """.trimIndent())
}

tasks.named("build") {
    dependsOn("darwinCmake")
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
            binaries.all {
                freeCompilerArgs += listOf("-linker-option", file("cpp/build/Release-iphonesimulator/lib${Name}.a").absolutePath)
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