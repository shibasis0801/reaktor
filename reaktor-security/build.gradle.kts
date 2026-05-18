import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

val androidOpenSsl = "com.android.ndk.thirdparty:openssl:1.1.1q-beta-1"

dependeasy {
    androidNative {
        prefab(
            cmakeVariable = "OPENSSL_CRYPTO_PREFAB_DIR",
            dependencyNotation = androidOpenSsl,
            moduleName = "crypto"
        )
        prefab(
            cmakeVariable = "OPENSSL_SSL_PREFAB_DIR",
            dependencyNotation = androidOpenSsl,
            moduleName = "ssl"
        )
    }

    darwinNative {
        packageName = "dev.shibasis.reaktor.security.native"
        includeDirs(
            "cpp/rsec-mls-capi/include",
            "cpp/rsec-mls-core/include"
        )
        headers("cpp/rsec-mls-capi/include/rsec/rsec.h")
    }
}

kotlin {
    common {
        dependencies {
            commonCoroutines()
            api(project(":reaktor-core"))
        }
    }

    droid {
        dependencies {
            implementation(androidOpenSsl)
        }
    }
    darwin()
    server {}
    web()
}

dependencies {
    add("kspCommonMainMetadata", project(":reaktor-compiler"))
}

android {
    defaults("dev.shibasis.reaktor.security")
}
