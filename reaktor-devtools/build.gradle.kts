import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The agent that ships inside every Reaktor app. It is deliberately built out of framework parts
// the app already carries: `reaktor-service` declares the protocol once for both ends, Compose
// supplies the one element tree that works on Android and iOS alike, and `reaktor-graph-port`
// supplies the K1 interceptor the tap installs on. No new transport, no second serializer.
kotlin {
    common {
        dependencies {
            api(project(":reaktor-service"))
            api(project(":reaktor-graph-port"))
            api(compose.runtime)
            api(compose.ui)
            commonCoroutines()
        }
    }
    droid {}
    darwin {}
    server {}
    web {}

    // Sockets are the agent's transport everywhere except the browser, which cannot listen at all
    // and dials the workbench instead. Keeping `ktor-network` in its own source set is what lets
    // the module keep a JS target rather than forcing every consumer to drop one.
    applyDefaultHierarchyTemplate()
    sourceSets {
        val commonMain by getting
        val socketMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("io.ktor:ktor-network:${Version.Ktor}")
            }
        }
        listOf("jvmMain", "androidMain", "iosMain").forEach { name ->
            findByName(name)?.dependsOn(socketMain)
        }

        // Android and the desktop are both JVMs and share `java.io`, but the default hierarchy
        // gives them no common source set. One exists here so the handful of genuinely shared
        // JVM implementations are written once.
        val jvmSharedMain by creating { dependsOn(commonMain) }
        listOf("jvmMain", "androidMain").forEach { name ->
            findByName(name)?.dependsOn(jvmSharedMain)
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.devtools")
}
