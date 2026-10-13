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
                api(projects.reaktorAuth)
                api(projects.reaktorWork)
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
                implementation(projects.reaktorCrypto)
            }
            testDependencies {
                implementation(Versions.Data.MockWebServer)
                implementation(Versions.Data.Postgres)
            }
        }
    }
}
