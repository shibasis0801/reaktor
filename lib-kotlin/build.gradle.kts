plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.21"
    id("com.android.library")
}

kotlin {
    targetHierarchy.default()

    jvmToolchain(17)
    android() {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "shared"
        }

        // See this
        // https://www.youtube.com/watch?v=Z2PHpxVD9_s&ab_channel=Xebia

        it.compilations.getByName("main").cinterops {
            val reaktor by creating {
                defFile(file("cpp/reaktor.def"))
                headers("cpp/Reaktor.h")
                extraOpts("-Xsource-compiler-option", "-std=c++20")
                extraOpts("-Xsource-compiler-option", "-stdlib=libc++")
                extraOpts("-Xcompile-source", "cpp/Reaktor.cpp")
//                linkerOpts("-L../../flatbuffers/libflatbuffers.a")
//                extraOpts("-Xsource-compiler-option", "-I../../core-cpp")
            }
        }
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.ExperimentalUnsignedTypes")
            }
        }

        val commonMain by getting {
            dependencies {
                implementation(project(":flatbuffers-kotlin"))
                api("io.ktor:ktor-client-core:2.3.0")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("com.facebook.fbjni:fbjni:0.4.0")
                api("io.ktor:ktor-client-okhttp:2.3.0")
            }
        }

        val iosMain by getting {
            dependencies {
                api("io.ktor:ktor-client-darwin:2.3.0")
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("junit:junit:4.13.2")
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("androidx.test:core-ktx:1.5.0")
                implementation("junit:junit:4.13.2")
                implementation("androidx.test.ext:junit:1.1.3")
                implementation("androidx.test.ext:junit-ktx:1.1.3")
                implementation("androidx.test.espresso:espresso-core:3.4.0")
            }
        }
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}


android {
    compileSdk = 33
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    defaultConfig {
        minSdk = 23
        externalNativeBuild {
            cmake {
                cFlags.addAll(listOf("-Wall", "-Werror", "-fexceptions", "-fPIC", "-frtti", "-DWITH_INSPECTOR=1"))
                arguments.addAll(listOf("-DCMAKE_VERBOSE_MAKEFILE=1", "-DANDROID_STL=c++_shared"))
                cppFlags.add("-std=c++17")
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    externalNativeBuild {
        cmake {
            path = file("cpp/CMakeLists.txt")
        }
    }
    namespace = "com.jetbrains.android"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        prefab = true
    }

    packagingOptions {
        pickFirst("**/libc++_shared.so") // dangerous
        pickFirst("**/libfolly_runtime.so")
        pickFirst("**/libglog.so")
        pickFirst("**/libfbjni.so")
    }
}
