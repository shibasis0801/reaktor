import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.web.kotlinWrappers
import dev.shibasis.dependeasy.web.react
import dev.shibasis.dependeasy.web.webCoroutines
import dev.shibasis.dependeasy.android.activityFragment
import dev.shibasis.dependeasy.android.androidCoroutines
import dev.shibasis.dependeasy.android.extensions
import dev.shibasis.dependeasy.android.fbjni

import dev.shibasis.dependeasy.dependencies.useKoin

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    javascript("uiTypeScript").library("buildUiTypeScript", "ts/tsconfig.json", "ts/dist")
    module("dev.shibasis.reaktor.ui") {
        common {
            dependencies {
                api(project(":reaktor-core"))
                api(project(":reaktor-code"))
                api(project(":reaktor-io"))
                api(project(":reaktor-web"))
                api(project(":reaktor-surface-compose"))
                api(Versions.Compose.Runtime)
                api(Versions.Compose.Foundation)
                api(Versions.Compose.Material3)
                api(Versions.Compose.MaterialIcons)
                // Common BackHandler — needed by any full-screen overlay that must swallow back
                // instead of letting it pop the route underneath.
                api(Versions.Compose.BackHandler)
                api(Versions.Compose.Coil)
                api(Versions.Compose.CoilKtor)
                api(Versions.Compose.CoilSvg)
            }
            testDependencies {
                implementation(Versions.Compose.UiTest)
            }
        }

        web {
            dependencies {
                kotlinWrappers()
                react()
                webCoroutines()
            }
        }

        android {
            dependencies {
                activityFragment()
                androidCoroutines()
                fbjni()

                extensions()
            }
        }

        apple {
            dependencies {
                // todo kotlin native needs this, should be transitive but there is some bug https://github.com/Kotlin/kotlinx.coroutines/pull/3996/files
                api(Versions.Kotlin.AtomicFu)
            }
        }

        jvm {
            dependencies {
                api(Versions.Compose.Desktop)
            }
            testDependencies {
                implementation(project(":reaktor-performance"))
            }
        }
    }


    jvmClasspath("jvmComposeWebViewProbeClasspath", "reports/webview/compose-probe-classpath.txt")
}
