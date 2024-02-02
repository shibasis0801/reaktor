import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
//    id("org.jetbrains.reflekt") version "1.7.0"
    id("com.android.library")
}

fun cmd(command: String) {
    ProcessBuilder(*command.split(" ").toTypedArray())
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
        .waitFor()
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
    targetHierarchy.default()

    jvmToolchain(17)
    android() {
        compilations.all {
            kotlinOptions {
//                jvmTarget = "1.8"
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
            baseName = "flatinvoker"
        }

        // See this
        // https://www.youtube.com/watch?v=Z2PHpxVD9_s&ab_channel=Xebia
        it.compilations.getByName("main").cinterops {
            val reaktor by creating {
                extraOpts("-Xsource-compiler-option", "-std=c++20")
                extraOpts("-Xsource-compiler-option", "-stdlib=libc++")
                defFile(file("cpp/reaktor.def"))
                // For a given directory, recursively add all headers
                headers("cpp/darwin/Reaktor.h", "cpp/common/Flex.h")
            }
        }
    }

    sourceSets {
        all {
            languageSettings.apply {
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("kotlin.ExperimentalUnsignedTypes")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
            }
        }

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

        androidMain.dependencies {
            implementation("com.facebook.fbjni:fbjni:0.2.2")
            api("io.ktor:ktor-client-okhttp:2.3.0")
        }
        iosMain.dependencies {
            api("io.ktor:ktor-client-darwin:2.3.0")
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
    namespace = "dev.shibasis.reaktor.flatinvoker"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

tasks.withType<KotlinCompile>().all {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

