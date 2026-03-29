import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*


plugins {
    id("dev.shibasis.dependeasy.library")
    
}

group = "dev.shibasis.flatinvoker.react"
version = "1.0-SNAPSHOT"

dependeasy {
    androidNative {
        fbjni()
    }
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
            api("com.facebook.react:react-native:0.68.5") {
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
