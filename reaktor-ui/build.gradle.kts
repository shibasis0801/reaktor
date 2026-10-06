import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api(project(":reaktor-code"))
            api(project(":reaktor-io"))
            api(project(":reaktor-web"))
            api(project(":reaktor-surface-compose"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            // Common BackHandler — needed by any full-screen overlay that must swallow back
            // instead of letting it pop the route underneath.
            api("org.jetbrains.compose.ui:ui-backhandler:${project.property("compose.version")}")
            api("io.coil-kt.coil3:coil-compose:3.2.0")
            api("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
            api("io.coil-kt.coil3:coil-svg:3.2.0")
        }
        testDependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            api(compose.uiTest)
        }
    }

    web {
        webpackConfig {
            configDirectory = file("${projectDir}/webpack.config.d")
        }
        dependencies {
            kotlinWrappers()
            react()
            webCoroutines()
        }
        packageJson = file("package.json")
    }

    droid {
        dependencies {
            activityFragment()
            androidCoroutines()
            fbjni()
//            lifecycle()
            extensions()
        }
    }

    darwin {
        dependencies {
            // todo kotlin native needs this, should be transitive but there is some bug https://github.com/Kotlin/kotlinx.coroutines/pull/3996/files
            api("org.jetbrains.kotlinx:atomicfu:0.23.1")
        }
    }
    server {
        dependencies {
            api(compose.desktop.currentOs)
        }
    }
    sourceSets.named("jvmTest") {
        dependencies {
            implementation(project(":reaktor-performance"))
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.ui")
}

tasks.register("jvmComposeWebViewProbeClasspath") {
    dependsOn("jvmTestClasses")
    val target = kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
    val compilation = target.compilations.getByName("test")
    val classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    val output = layout.buildDirectory.file("reports/webview/compose-probe-classpath.txt")
    inputs.files(classpath)
    outputs.file(output)
    doLast { output.get().asFile.apply { parentFile.mkdirs(); writeText(classpath.files.joinToString("\n") { it.absolutePath }) } }
}
