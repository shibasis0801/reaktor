import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    applyHierarchyTemplate(KotlinHierarchyTemplate.default)

    common {
        dependencies = {
            api(project(":reaktor-core"))
            api("org.jetbrains.kotlinx:kotlinx-io-core:0.3.1")
//            api("androidx.datastore:datastore-preferences-core:1.1.0")
            commonNetworking()
            commonLogging()
        }
    }

    // https://web.dev/articles/origin-private-file-system
    // https://developer.chrome.com/blog/sqlite-wasm-in-the-browser-backed-by-the-origin-private-file-system
    web {
        dependencies = {
            webNetworking()
        }
    }

    droid {
        dependencies = {
            firebase(project)
            androidNetworking()
            api("androidx.datastore:datastore-preferences-core:1.1.0")
        }
    }

    darwin {
        dependencies = {
            darwinNetworking()
            api("androidx.datastore:datastore-preferences-core:1.1.0")
        }
    }
    server {
        dependencies = {
            serverNetworking()
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.io")
}


//dependencies {
//    add("kspCommonMainMetadata", project(":flatinvoker-compiler"))
//}
