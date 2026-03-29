import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
    
}

dependeasy {
    androidNative {
        fbjni()
        hermes("0.81.4")
    }
}

kotlin {
    common {
        dependencies {
            implementation(project(":flatbuffers-kotlin"))
            api(project(":reaktor-core"))
            api(project(":reaktor-flexbuffer"))
        }
    }

    droid {
        dependencies {
            implementation("com.facebook.react:hermes-android:0.81.4")
        }
        integrationTestDependencies {
//            api()
        }
    }

    web {}

    server {}

    darwin()
}

dependencies { add("kspCommonMainMetadata", project(":reaktor-compiler")) }

android {
   defaults("dev.shibasis.reaktor.ffi")
}
