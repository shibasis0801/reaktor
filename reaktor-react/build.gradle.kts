import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*


plugins {
    id("dev.shibasis.dependeasy.library")

}

dependeasy {
    group = "dev.shibasis.flatinvoker.react"
    version = "1.0-SNAPSHOT"

    androidNative {
        fbjni()
    }


    kotlin {
        common {
            dependencies = {
                api(project(":reaktor-core"))
                api(project(":reaktor-io"))
                api(project(":flatinvoker-core"))
            }
        }

        droid {
            dependencies = {
                api(Versions.Native.ReactNative) {
                    exclude(module = "fbjni-java-only")
                }
            }
        }

        darwin {

        }
    }


    android {
        defaults("dev.shibasis.flatinvoker.react")
    }
}
