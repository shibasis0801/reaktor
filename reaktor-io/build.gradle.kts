import com.codingfeline.buildkonfig.compiler.FieldSpec
import com.codingfeline.buildkonfig.gradle.BuildKonfigTask
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.dependencies.useKoin
import dev.shibasis.dependeasy.dependencies.useNetworking
import java.net.InetSocketAddress
import java.net.Socket
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("dev.shibasis.dependeasy.library")
    id("com.codingfeline.buildkonfig")
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.3.1")
        }
    }

    // https://web.dev/articles/origin-private-file-system
    // https://developer.chrome.com/blog/sqlite-wasm-in-the-browser-backed-by-the-origin-private-file-system
    web {}
    droid {}
    darwin {}
    server {}
    useNetworking()
}

android {
    defaults("dev.shibasis.reaktor.io")
}


dependencies {
    add("kspCommonMainMetadata", project(":reaktor-compiler"))
    add("kspJs", project(":reaktor-compiler"))
}


buildkonfig {
    packageName = "dev.shibasis.reaktor.core"
    objectName = "BuildKonfig"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "SERVER", getMachineIpAddress())
    }
}

fun getMachineIpAddress(): String = Socket().run {
//    connect(InetSocketAddress("google.com", 80))
    localAddress.hostAddress
}

tasks.getByName("build").dependsOn(tasks.withType<BuildKonfigTask>())