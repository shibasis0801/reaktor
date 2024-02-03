import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
//    id("com.google.firebase.crashlytics")
    id("com.google.devtools.ksp")
    id("org.jetbrains.compose")
}

kotlin {
    val (commonMain, commonTest) = common {
        dependencies = {
            commonNetworking()
            commonCoroutines()
            commonSerialization()
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.3.0")
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(compose.materialIconsExtended)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            api(compose.components.resources)
            // Needs upgrade for wasm
            api("dev.chrisbanes.material3:material3-window-size-class-multiplatform:0.3.1")
            api("co.touchlab:kermit:2.0.2") //Add latest version
        }

        testDependencies = {
            api(kotlin("test"))
        }
    }

    web {
        dependencies = {
            api(npm("sql.js", "1.6.2"))
            api(devNpm("copy-webpack-plugin", "9.1.0"))
            webBasic()
            react()
            webNetworking()
            webCoroutines()
        }
    }

    droid {
        targetModifier = {

        }
        dependencies = {
            basic()
//                flipper()
            androidCompose(project)
            androidCoroutines()
            lifecycle()
            networking()
            workManager()
            extensions()
            camera()
            firebase(project)
        }
    }

    darwin {
        dependencies = {
            api("io.ktor:ktor-client-darwin:${Version.Ktor}")
        }
    }
    server {
        targetModifier = {
            jvmToolchain(11)
        }
        dependencies = {
            api("app.cash.sqldelight:sqlite-driver:2.0.0")
            vertx()
            serverNetworking()
            api("com.google.firebase:firebase-admin:9.2.0")
        }
    }
}

android {
    defaults("dev.reaktor.core")
}


dependencies {
    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
}

tasks.register<Copy>("copyDokka") {
    group = "reaktor"
    dependsOn("dokkaHtml")
    from("$buildDir/dokka/html")
    into("docs/${project.name}")
}

