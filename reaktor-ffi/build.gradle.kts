import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("dev.shibasis.dependeasy.library")
    
}
val Name = "ReaktorFFI"

kotlin {
    jvmToolchain(11)
    common {
        dependencies {
            implementation(project(":flatbuffers-kotlin"))
            api(project(":reaktor-core"))
            api(project(":reaktor-flexbuffer"))
        }
    }

    droid {
        dependencies {
            implementation("com.facebook.react:hermes-android:0.81.4")
            // They have coupled this with react native, and download latest main as a tarball
            // https://github.com/facebook/react-native/blob/a9a1c86a927fc6e3854a9b4ad44d38bd3c8db588/packages/react-native/ReactAndroid/hermes-engine/build.gradle.kts#L336

        }
        integrationTestDependencies {
//            api()
        }
    }

    darwin {
        cinterops {
            val reaktor by creating {
                extraOpts("-Xsource-compiler-option", "-std=c++20")
                extraOpts("-Xsource-compiler-option", "-stdlib=libc++")
                packageName("dev.shibasis.reaktor.native")
                includeDirs("../reaktor-flexbuffer/cpp")
                includeDirs("cpp")
                defFile(file("cpp/bindings.def"))
                headers("cpp/darwin/DarwinInvokable.h", "cpp/common/Invokable.h", "../reaktor-flexbuffer/cpp/common/CBase.h", "../reaktor-flexbuffer/cpp/common/Flex.h")
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
}

dependencies { add("kspCommonMainMetadata", project(":reaktor-compiler")) }

android {
   defaults("dev.shibasis.reaktor.ffi", file("cpp/CMakeLists.txt"))
}
