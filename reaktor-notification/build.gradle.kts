import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
    
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api(project(":reaktor-ui"))
            commonCoroutines()
            commonSerialization()
        }
    }
    droid {
        dependencies {
            implementation(project.dependencies.platform("com.google.firebase:firebase-bom:33.1.1"))
            implementation("com.google.firebase:firebase-messaging")
        }
    }
    darwin {}
    web {}
    server {

    }
    useKoin()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test"))
            commonCoroutines()
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.notification")
}
