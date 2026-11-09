import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

// Reading the text out of a PDF. Its own module because of what it brings: PDFBox on the JVM and
// pdf.js in a browser are each a megabyte or more, and an app that never opens a PDF should not
// carry either. Android uses the platform's reader and adds nothing. No reader on Apple yet: PDFKit
// can do it, and nothing has been written or run, so nothing is claimed.
library(namespace = "dev.shibasis.reaktor.pdf", targets = Targets.All) {
    dependencies {
        common {
            api(projects.reaktorCore)
            api(Versions.Kotlin.CoroutinesCore)
        }
        web { implementation(npm("pdfjs-dist", "4.10.38")) }
        jvm { implementation("org.apache.pdfbox:pdfbox:3.0.5") }
    }
}

// Only classes whose names end in `Test` are suites; fixtures are not.
tasks.withType<Test>().configureEach {
    include("**/*Test.class")
}
