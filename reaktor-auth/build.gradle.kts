import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*
import org.jetbrains.kotlin.gradle.plugin.KotlinHierarchyTemplate

plugins {
    id("dev.shibasis.dependeasy.library")
    id("app.cash.sqldelight")
}

kotlin {
    common {
        dependencies = {

        }
    }

    web {
        dependencies = {

        }
    }

    droid {
        dependencies = {

        }
    }

    darwin {
        dependencies = {

        }
    }
    server {
        dependencies = {

        }
    }
}

android {
    defaults("dev.shibasis.reaktor.io")
}
