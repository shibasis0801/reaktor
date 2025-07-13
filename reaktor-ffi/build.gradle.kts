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
    jvmToolchain(11)
    common {
        dependencies = {
            implementation(project(":flatbuffers-kotlin"))
            api(project(":reaktor-core"))
            api(project(":reaktor-flexbuffer"))
        }
    }

    droid {
        dependencies {
            implementation("com.facebook.react:hermes-android:0.76.1")
            // They have coupled this with react native, and download latest main as a tarball
            // https://github.com/facebook/react-native/blob/a9a1c86a927fc6e3854a9b4ad44d38bd3c8db588/packages/react-native/ReactAndroid/hermes-engine/build.gradle.kts#L336

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
   defaults("dev.shibasis.flatinvoker.ffi", file("cpp/CMakeLists.txt"))
}
