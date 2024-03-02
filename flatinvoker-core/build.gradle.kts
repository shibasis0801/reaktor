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
    id("com.codingfeline.buildkonfig")
}

buildkonfig {
    packageName = "dev.shibasis.flatinvoker.core"
    defaultConfigs {
        buildConfigField(STRING, "name", "value")
    }
}

task<Exec>("darwinCmake") {
    group = "reaktor"
    // environment variable for react location
    commandLine =  listOf("bash", "-c", """
        rm -rf build &&
        cd cpp &&
        rm -rf build &&
        cmake -DNAME=FlatInvokerCore -B build -G Xcode &&
        cmake --build build --config Release
    """.trimIndent())
}

tasks.named("build") {
    dependsOn("darwinCmake")
}


kotlin {
    common {
        dependencies = {
            implementation(project(":flatbuffers-kotlin"))
            api(project(":reaktor-core"))
            // Temporary Dependencies. We should have pure framework code in here.
            api(project(":reaktor-io"))
        }
    }

    droid {}

    darwin {
        cinterops = {
            val reaktor by creating {
                extraOpts("-Xsource-compiler-option", "-std=c++20")
                extraOpts("-Xsource-compiler-option", "-stdlib=libc++")
                packageName("dev.shibasis.reaktor.native")
                // todo create def file if not present
                defFile(file("cpp/bindings.def"))
                headers("cpp/darwin/Reaktor.h", "cpp/common/Flex.h")
            }
        }

        targets = {
            binaries.all {
                freeCompilerArgs += listOf("-linker-option", "/Users/ovd/IdeaProjects/flatInvoker/flatinvoker-core/cpp/build/Release-iphonesimulator/libFlatInvokerCore.a")
            }
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
}

android {
   defaults("dev.shibasis.flatinvoker.core", file("cpp/CMakeLists.txt"), "FlatInvokerCore")
}