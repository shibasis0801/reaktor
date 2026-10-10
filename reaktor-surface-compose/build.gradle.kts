import dev.shibasis.dependeasy.Versions
plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.reaktor.surface.compose") {
        common {
            dependencies {
                api(project(":reaktor-surface"))
                api(Versions.Compose.Runtime)
                api(Versions.Compose.Foundation)
                api(Versions.Compose.Ui)
                api(Versions.Compose.BackHandler)
            }
            testDependencies {
                implementation(Versions.Compose.UiTest)
            }
        }
        android {}
        apple {}
        web {}

        jvm {
            dependencies {
                api(Versions.Compose.Desktop)
            }
            testDependencies {
                implementation(Versions.Kotlin.CoroutinesDebug)
            }
        }
    }
}
