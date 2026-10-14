import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    val bindings = javascript("karakum", "ts").bindings("karakum.config.json", "import")
    dag("bindings") { target("generateBindings", node(bindings)) }

    androidNative {
        prefab(
            cmakeVariable = "OPENSSL_CRYPTO_PREFAB_DIR",
            dependencyNotation = Versions.Native.OpenSsl,
            moduleName = "crypto"
        )
        prefab(
            cmakeVariable = "OPENSSL_SSL_PREFAB_DIR",
            dependencyNotation = Versions.Native.OpenSsl,
            moduleName = "ssl"
        )
    }

    darwinNative {
        packageName = "dev.shibasis.reaktor.security.native"
        headers("src/commonMain/cpp/include/rsec/rsec.h")
    }
    module("dev.shibasis.reaktor.security") {
        common {
            dependencies {
                commonCoroutines()
                api(projects.reaktorCore)
            }
        }

        android {}
        apple {}

        jvm {
            dependencies { implementation(Versions.Native.Jna) }
        }
        web { sourceSetModifier { kotlin.srcDir(bindings) } }
    }
    kotlinProcessor(projects.reaktorCompiler)
}
