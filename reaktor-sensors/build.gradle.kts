import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*

plugins {
    id("com.android.library")
    id("dev.shibasis.dependeasy.library")
}

// Android-only for now: the step counter has no meaningful implementation on the other targets
// yet. Add darwin{}/web{} alongside their actuals rather than shipping empty ones.
kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
        }
    }
    droid {}
}

android {
    defaults("dev.shibasis.reaktor.sensors")
}
