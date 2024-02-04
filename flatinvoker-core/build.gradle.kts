import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
}

task<Exec>("darwinCmake") {
    group = "reaktor"
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
    droid {
        dependencies = {
            implementation("com.facebook.fbjni:fbjni:0.2.2")
            api("io.ktor:ktor-client-okhttp:2.3.0")
        }
    }

    darwin {
        cinterops = {
            val reaktor by creating {
                extraOpts("-Xsource-compiler-option", "-std=c++20")
                extraOpts("-Xsource-compiler-option", "-stdlib=libc++")
                packageName("dev.shibasis.reaktor.native")
//                defFile(file("cpp/reaktor.def"))

                // For a given directory, recursively add all headers
                headers("cpp/darwin/Reaktor.h", "cpp/common/Flex.h")
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

    sourceSets {
        commonMain.dependencies {
            implementation(project(":flatbuffers-kotlin"))
            api("io.ktor:ktor-client-core:2.3.0")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test-common"))
            implementation(kotlin("test-annotations-common"))
        }
    }
}

android {
   defaults("dev.shibasis.reaktor.flatinvoker", file("cpp/CMakeLists.txt"))
}