import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.Compose
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.auth", targets = Targets.All, ui = Compose()) {
    val packageSources by webPackage("ts")
    dependencies {
        common {
            api(Reaktor.AuthCore, Reaktor.Ui, Reaktor.Graph, Reaktor.Service, Reaktor.Db, Reaktor.Io)
            api(Versions.Data.Jwt)
        }
        android {
            implementation(Versions.Android.Credentials)
            implementation(Versions.Android.CredentialsPlayServices)
            implementation(Versions.Android.GoogleId)
        }
        jvm {
            api(Reaktor.Security, Reaktor.Tooling)
            api(Versions.Server.OAuth)
            api(Versions.Server.Security)
            api(Versions.Data.Exposed)
            api(Versions.Data.ExposedJdbc)
            api(Versions.Data.ExposedJson)
            api(Versions.Data.Postgres)
            api(Versions.Kotlin.Reflect)
        }
        jvmTest { implementation(Versions.Data.H2) }
    }
}
