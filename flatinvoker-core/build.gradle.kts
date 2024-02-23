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
            api("io.ktor:ktor-client-core:2.3.0")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
        }
    }

    droid {
        dependencies = {
            api("io.ktor:ktor-client-okhttp:2.3.0")
        }
    }

    darwin {
        cinterops = {
            val reaktor by creating {
                extraOpts("-Xsource-compiler-option", "-std=c++20")
                extraOpts("-Xsource-compiler-option", "-stdlib=libc++")
                packageName("dev.shibasis.reaktor.native")
                defFile(file("cpp/reaktor.def"))
                headers("cpp/darwin/Reaktor.h", "cpp/common/Flex.h")

//                val folders = listOf(
//                    "${REACT_NATIVE}/React",
//                    "${REACT_NATIVE}/React/Base",
//                    "${REACT_NATIVE}/ReactCommon/jsi",
//                    "${REACT_NATIVE}/ReactCommon/jsi/jsi",
//                    "${REACT_NATIVE}/ReactCommon/callinvoker"
//                )
                // list all files in these folders with extension .h and add them as headers
//                headers(folders.flatMap { folder ->
//                    fileTree(folder) {
//                        include("**/*.h")
//                    }.files
//                })

//                val REACT_NATIVE = "../tester-react/node_modules/react-native"
//                compilerOpts("-I${REACT_NATIVE}")
//                headers(listOf("RCTDefines.h", "RCTJSThread.h").map { "${REACT_NATIVE}/React/Base/${it}" })
            }
        }

        targets = {
            binaries.all {
                freeCompilerArgs += listOf("-linker-option", "/Users/ovd/IdeaProjects/flatInvoker/flatinvoker-core/cpp/build/Release-iphonesimulator/libReaktor.a")
            }
        }

        dependencies = {
            api("io.ktor:ktor-client-darwin:2.3.0")
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
}

android {
   defaults("dev.shibasis.reaktor.flatinvoker", file("cpp/CMakeLists.txt"))
}