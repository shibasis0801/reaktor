import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
    
}

kotlin {
    common {
        dependencies = {
            api(project(":reaktor-ui"))
        }
    }
    droid {}
    darwin {}
    web {}
    server {}
    useKoin()
}

android {
    defaults("dev.shibasis.reaktor.navigation")
}
