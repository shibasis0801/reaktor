import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
}

dependeasy {

}

kotlin {
    val (commonMain, commonTest) = common {
        dependencies = {
            api(project(":reaktor-core"))
            api(project(":lib-navigation"))
        }
    }

    darwin {
        podDependencies = {

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
}

android {
    defaults("app.mehmaan.media")
}
