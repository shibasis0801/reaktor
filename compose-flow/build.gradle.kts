import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.android.activityFragment
import dev.shibasis.dependeasy.android.androidCoroutines
import dev.shibasis.dependeasy.android.extensions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.configuration.ReaktorTests
import dev.shibasis.dependeasy.model.Suite
import dev.shibasis.dependeasy.model.Compose
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.web.webCoroutines

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.composeflow", targets = Targets.All, ui = Compose()) {
    dependencies {
        common {
            api(Versions.Compose.Runtime)
            api(Versions.Compose.Foundation)
            api(Versions.Compose.Material3)
        }

        web {
            webCoroutines()
        }

        android {
            activityFragment()
            androidCoroutines()
            extensions()
        }

        apple {
            api(Versions.Kotlin.AtomicFu)
        }

        jvm {
            api(Versions.Compose.Desktop)
        }
    }
    val unit by tests(Suite.Jvm, environment = ReaktorTests.Headless)
}
