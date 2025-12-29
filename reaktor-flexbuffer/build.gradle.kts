@file:Suppress("UnstableApiUsage")

import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*


plugins {
    id("dev.shibasis.dependeasy.library")
    
}

val Name = "ReaktorFlexbuffer"

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

    darwin {
        cinterops {
            val reaktor by creating {
                extraOpts("-Xsource-compiler-option", "-std=c++20")
                extraOpts("-Xsource-compiler-option", "-stdlib=libc++")
                packageName("dev.shibasis.reaktor.native")
                // todo create def file if not present
                defFile(file("cpp/bindings.def"))
                includeDirs("cpp")
                headers("cpp/common/CBase.h", "cpp/darwin/Reaktor.h", "cpp/common/Flex.h")
            }
        }

        targetModifier {
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
    add("kspCommonMainMetadata", project(":reaktor-compiler"))
}

android {
   defaults("dev.shibasis.reaktor.core", file("cpp/CMakeLists.txt"), Name)
}
