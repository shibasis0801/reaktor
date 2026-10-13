import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*

plugins {
    id("dev.shibasis.dependeasy.library")
}

// Reading the text out of a PDF. Its own module because of what it brings: PDFBox on the JVM and
// pdf.js in a browser are each a megabyte or more, and an app that never opens a PDF should not
// carry either. Android uses the platform's reader and adds nothing.
kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            commonCoroutines()
        }
    }
    droid {}
    // No reader here yet. PDFKit can do it; nothing has been written or run, so nothing is claimed.
    darwin {}
    web {
        dependencies {
            implementation(npm("pdfjs-dist", "4.10.38"))
        }
    }
    server {
        dependencies {
            implementation("org.apache.pdfbox:pdfbox:3.0.5")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.pdf")
}

// Only classes whose names end in `Test` are suites; fixtures are not.
tasks.withType<Test>().configureEach {
    include("**/*Test.class")
}
