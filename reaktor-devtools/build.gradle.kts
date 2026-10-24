import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Compose
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.devtools", targets = Targets.All, ui = Compose()) {
    dependencies {
        common {
            api(Reaktor.Service, Reaktor.Work, Reaktor.Performance, Reaktor.GraphPort)
            api(Versions.Compose.Runtime)
            api(Versions.Compose.Ui)
            commonCoroutines()
        }
        android {
            implementation(Versions.Android.Activity)
            implementation(Versions.Kotlin.KtorNetwork)
        }
        apple { implementation(Versions.Kotlin.KtorNetwork) }
        jvm {
            implementation(Versions.Kotlin.KtorNetwork)
            implementation(Versions.Kotlin.KtorWebSockets)
        }
    }
    sources("src/socketMain/kotlin", targets = Targets.Android + Targets.Apple + Targets.Jvm)
    sources("src/jvmSharedMain/kotlin", targets = Targets.Android + Targets.Jvm)
}
