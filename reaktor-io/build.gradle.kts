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

plugins {
    id("dev.shibasis.dependeasy.library")
    id("com.codingfeline.buildkonfig")
}

kotlin {
    common {
        dependencies = {
            api(project(":reaktor-core"))
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.3.1")
//            api("androidx.datastore:datastore-preferences-core:1.1.0")
        }
    }

    // https://web.dev/articles/origin-private-file-system
    // https://developer.chrome.com/blog/sqlite-wasm-in-the-browser-backed-by-the-origin-private-file-system
    web {
        dependencies = {}
    }

    droid {
        dependencies = {
            api("androidx.datastore:datastore-preferences-core:1.1.0")
        }
    }

    darwin {
        dependencies = {
            api("androidx.datastore:datastore-preferences-core:1.1.0")
        }
    }
    server {}
    useNetworking()
}

android {
    defaults("dev.shibasis.reaktor.io")
}


//dependencies {
//    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
//}


buildkonfig {
    packageName = "dev.shibasis.reaktor.core"
    objectName = "BuildKonfig"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "SERVER", getMachineIpAddress())
    }
}

fun getMachineIpAddress(): String = Socket().run {
    connect(InetSocketAddress("google.com", 80))
    localAddress.hostAddress
}

tasks.getByName("build").dependsOn(tasks.withType<BuildKonfigTask>())
dependencies {
    implementation("androidx.wear.compose:compose-material-core:1.4.1")
}
