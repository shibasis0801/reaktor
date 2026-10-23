import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.Compose
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.google", targets = Targets.All, ui = Compose()) {
    val generateBindings by bindings()
    val buildTypeScript by typescript(directory = "ts", configuration = "tsconfig.json", output = "dist", kotlin = listOf(kotlinJsLibrary(Reaktor.Google)))
    dependencies {
        common { api(projects.reaktorAuth); api(projects.reaktorWork) }
        jvm {
            api(Versions.Google.Sheets)
            api(Versions.Google.Pubsub)
            implementation(Versions.Google.Calendar)
            implementation(Versions.Google.Drive)
            implementation(Versions.Google.YouTube)
            api(Versions.Google.Auth)
            implementation(projects.reaktorCrypto)
        }
        jvmTest {
            implementation(Versions.Data.MockWebServer)
            implementation(Versions.Data.Postgres)
        }
    }
}
