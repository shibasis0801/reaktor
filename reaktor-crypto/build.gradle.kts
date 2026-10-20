import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.Targets

plugins { id("dev.shibasis.dependeasy.library") }

// Portable crypto primitives; Android and JVM share the JCA implementation.
library(namespace = "dev.shibasis.reaktor.crypto", targets = Targets.Android + Targets.Jvm + Targets.Web) {
    sources(directory = "src/jvmShared/kotlin", targets = Targets.Android + Targets.Jvm)
    dependencies {
        common { commonCoroutines() }
        commonTest { implementation(Versions.Kotlin.CoroutinesTest) }
    }
}
