import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

task<Exec>("darwinCmake") {
    group = "reaktor"
    // environment variable for react location
    commandLine =  listOf("bash", "-c", """
        rm -rf build &&
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
    jvmToolchain(11)
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
                defFile(file("cpp/bindings.def"))
                headers("cpp/darwin/Reaktor.h", "cpp/common/Flex.h")
            }
        }

        targets = {
            binaries.all {
                freeCompilerArgs += listOf("-linker-option", "/Users/ovd/IdeaProjects/flatInvoker/flatinvoker-core/cpp/build/Release-iphonesimulator/libReaktor.a")
            }
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
}

android {
   defaults("dev.shibasis.reaktor.flatinvoker", file("cpp/CMakeLists.txt"))
}