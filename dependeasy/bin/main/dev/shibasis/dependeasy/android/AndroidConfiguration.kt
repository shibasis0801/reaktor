package dev.shibasis.dependeasy.android

import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.common.Configuration
import dev.shibasis.dependeasy.native.hasNativeConfiguration
import dev.shibasis.dependeasy.native.nativeConfigurationOrNull
import dev.shibasis.dependeasy.native.nativeBuildDirectory
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.getting
import org.gradle.kotlin.dsl.invoke
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget

import dev.shibasis.dependeasy.tasks.droidCmake

class AndroidConfiguration(

): Configuration<KotlinAndroidTarget>() {
    internal var integrationTestDependencies: KotlinDependencyHandler.() -> Unit = {}
        private set

    fun integrationTestDependencies(fn: KotlinDependencyHandler.() -> Unit = {}) {
        this.integrationTestDependencies = fn
    }
}

fun KotlinMultiplatformExtension.droid(
    configuration: AndroidConfiguration.() -> Unit = {}
) {
    val configure = AndroidConfiguration().apply(configuration)
    val native = project.nativeConfigurationOrNull
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        publishLibraryVariants("release", "debug")

        compilerOptions {
            jvmTarget.set(Version.SDK.AndroidJava.asTarget)
            freeCompilerArgs.add("-Xstring-concat=inline")
        }

        configure.targetModifier(this)
    }

    if (project.hasNativeConfiguration) {
        val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") 
            ?: project.rootProject.file("local.properties")
                .takeIf { it.exists() }
                ?.let { java.util.Properties().apply { load(it.reader()) }.getProperty("sdk.dir") }
            ?: throw IllegalArgumentException("ANDROID_HOME not found")

        val cmakeTasks = dev.shibasis.dependeasy.Version.architectures.mapNotNull { abi ->
            project.droidCmake(
                abi = abi,
                sdkDir = sdkDir
            )
        }

        val generatedJniLibs = project.layout.buildDirectory.dir("dependeasy/jniLibs")
        val copyNativeLibs = project.tasks.register("copyNativeLibs", org.gradle.api.tasks.Copy::class.java) {
            dependsOn(cmakeTasks)
            dev.shibasis.dependeasy.Version.architectures.forEach { abi ->
                from(project.nativeBuildDirectory("android/$abi")) {
                    include("**/*.so")
                    into(abi)
                }
            }
            into(generatedJniLibs)
        }

        project.tasks.matching {
            it.name != "copyNativeLibs" && (
            it.name.contains("JniLib", ignoreCase = true) ||
                it.name.contains("NativeLib", ignoreCase = true) ||
                it.name.contains("DebugSymbols", ignoreCase = true)
            )
        }.configureEach {
            dependsOn(copyNativeLibs)
        }

        project.extensions.findByType(com.android.build.gradle.LibraryExtension::class.java)
            ?.sourceSets
            ?.getByName("main")
            ?.jniLibs
            ?.setSrcDirs(listOf(generatedJniLibs.get().asFile))
    }

    sourceSets {
        androidMain {
            configure.sourceSetModifier(this)
            dependencies {
                configure.dependencies(this)
                if (native?.android?.usesFbjni == true) {
                    fbjni()
                }
            }
        }

//        val androidUnitTest by getting {
//            dependencies {
//                implementation(kotlin("test-junit"))
//                implementation("junit:junit:4.13.2")
//                configure.testDependencies(this)
//            }
//        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("androidx.test:core-ktx:1.5.0")
                implementation("junit:junit:4.13.2")
                implementation("androidx.test.ext:junit:1.1.5")
                implementation("androidx.test.ext:junit-ktx:1.1.5")
                implementation("androidx.test.espresso:espresso-core:3.5.1")
                configure.integrationTestDependencies(this)
            }
        }
    }
}
