import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("dev.shibasis.dependeasy.library")
}
val Name = "FlatInvokerFFI"

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
            api(project(":flatinvoker-core"))
        }
    }

    droid {
        dependencies {
//            implementation(project(":hermes"))
        }
        integrationTestDependencies = {
//            api()
        }
    }

    darwin {
        cinterops = {
            val reaktor by creating {
                extraOpts("-Xsource-compiler-option", "-std=c++20")
                extraOpts("-Xsource-compiler-option", "-stdlib=libc++")
                packageName("dev.shibasis.reaktor.native")
                includeDirs("../flatinvoker-core/cpp")
                includeDirs("cpp")
                defFile(file("cpp/bindings.def"))
                headers("cpp/darwin/DarwinInvokable.h", "cpp/common/Invokable.h", "../flatinvoker-core/cpp/common/CBase.h", "../flatinvoker-core/cpp/common/Flex.h")
            }
        }

        targets = {
            binaries.all {
                freeCompilerArgs += listOf("-linker-option", "/Users/ovd/IdeaProjects/flatInvoker/flatinvoker-ffi/cpp/build/Release-iphonesimulator/lib${Name}.a")
            }
        }
    }

    server {

    }
}

dependencies { add("kspCommonMainMetadata", project(":flatinvoker-compiler")) }

android {
   defaults("dev.shibasis.flatinvoker.ffi", file("cpp/CMakeLists.txt"))
}
