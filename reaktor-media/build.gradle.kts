import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.android.camera
import dev.shibasis.dependeasy.android.workManager
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.compose-library") }

library(namespace = "dev.shibasis.reaktor.media", targets = Targets.Web + Targets.Android + Targets.Apple + Targets.Jvm) {
    dependencies {
        common {
            api(projects.reaktorService)
            api(projects.reaktorUi)
            api(projects.reaktorIo)
            api(Versions.Compose.Coil)
            api(Versions.Compose.CoilKtor)
        }
        android {
            camera()
            workManager()
            implementation(Versions.Android.PdfViewer)
            implementation(Versions.Android.PdfDocumentService)
        }
    }
}
