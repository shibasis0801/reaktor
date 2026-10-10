import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.dependencies.useKoin

plugins {
//    id("org.jetbrains.compose")
//    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.shibasis.dependeasy.compose-library")
}

dependeasy {
    module("dev.shibasis.reaktor.google") {
        common {
            dependencies {
                api(project(":reaktor-auth"))
                api(project(":reaktor-work"))
            }
        }
        android {}
        apple {}
        web {}

        jvm {
            dependencies {
                api(Versions.Google.Sheets)
                api(Versions.Google.Pubsub)
                implementation(Versions.Google.Calendar)
                implementation(Versions.Google.Drive)
                implementation(Versions.Google.YouTube)
                api(Versions.Google.Auth)
                implementation(project(":reaktor-crypto"))
            }
            testDependencies {
                implementation(Versions.Data.MockWebServer)
                implementation(Versions.Data.Postgres)
            }
        }
    }
}
