import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.web.kotlinWrappers
import dev.shibasis.dependeasy.web.react
import dev.shibasis.dependeasy.web.webCoroutines
import dev.shibasis.dependeasy.android.activityFragment
import dev.shibasis.dependeasy.android.androidCoroutines
import dev.shibasis.dependeasy.android.extensions
import dev.shibasis.dependeasy.android.fbjni
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Compose
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.compose-library") }

library(namespace = "dev.shibasis.reaktor.ui", targets = Targets.All, ui = Compose()) {
    val buildUiTypeScript by typescript(configuration = "ts/tsconfig.json", output = "ts/dist")
    val jvmComposeWebViewProbeClasspath by classpath(output = "reports/webview/compose-probe-classpath.txt")
    dependencies {
        common {
            api(Reaktor.Core, Reaktor.Code, Reaktor.Io, Reaktor.Web, Reaktor.SurfaceCompose)
            api(Versions.Compose.Runtime)
            api(Versions.Compose.Foundation)
            api(Versions.Compose.Material3)
            api(Versions.Compose.MaterialIcons)
            api(Versions.Compose.BackHandler)
            api(Versions.Compose.Coil)
            api(Versions.Compose.CoilKtor)
            api(Versions.Compose.CoilSvg)
        }
        commonTest { implementation(Versions.Compose.UiTest) }
        web {
            kotlinWrappers()
            react()
            webCoroutines()
        }
        android {
            activityFragment()
            androidCoroutines()
            fbjni()
            extensions()
        }
        apple { api(Versions.Kotlin.AtomicFu) }
        jvm { api(Versions.Compose.Desktop) }
        jvmTest { implementation(Reaktor.Performance) }
    }
}

