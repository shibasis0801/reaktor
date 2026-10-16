import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.android.activityFragment
import dev.shibasis.dependeasy.android.androidCoroutines
import dev.shibasis.dependeasy.android.extensions

import dev.shibasis.dependeasy.web.webCoroutines

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.composeflow") {
        common {
            dependencies {
                api(Versions.Compose.Runtime)
                api(Versions.Compose.Foundation)
                api(Versions.Compose.Material3)
            }
        }

        web {
            dependencies {
                webCoroutines()
            }
        }

        android {
            dependencies {
                activityFragment()
                androidCoroutines()
                extensions()
            }
        }

        apple {
            dependencies {
                api(Versions.Kotlin.AtomicFu)
            }
        }

        jvm {
            dependencies {
                api(Versions.Compose.Desktop)
            }
        }
    }


    tasks.withType<Test>().configureEach {
        systemProperty("java.awt.headless", "true")
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            // Exercise JDK native gesture dispatch and listener cleanup in the desktop regression.
            jvmArgs("--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
        }
    }

}
