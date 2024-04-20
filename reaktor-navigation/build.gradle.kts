import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
}

kotlin {
    val (commonMain, commonTest) = common {
        dependencies = {
            api(project(":reaktor-core"))
        }
    }
    droid {

    }
    darwin {

    }
    web {

    }
    server {

    }
}

android {
    defaults("dev.shibasis.reaktor.navigation")
}
