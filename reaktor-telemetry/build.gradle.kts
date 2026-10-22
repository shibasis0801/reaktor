import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.model.Suite
import dev.shibasis.dependeasy.model.TestEnvironment
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonNetworking

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.telemetry", targets = Targets.All) {
    dependencies {
        common {
            api(Reaktor.Core)
            commonNetworking()
            api(Reaktor.GraphRuntime)
            api(Versions.Telemetry.Api)
            api(Versions.Telemetry.Noop)
            implementation(Versions.Telemetry.Implementation)
        }
        commonTest { implementation(Versions.Kotlin.KtorMock) }
        android {
            api(project.dependencies.platform(Versions.Android.FirebaseBom))
            api(Versions.Android.FirebaseKotlinCrashlytics)
        }
    }
    val node by tests(Suite.JavaScript, environment = TestEnvironment(caseTimeoutMillis = 10_000))
}
