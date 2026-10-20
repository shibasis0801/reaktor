import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.common.commonSerialization
import dev.shibasis.dependeasy.configuration.ReaktorTests
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.model.*

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.conductor", targets = Targets.All, bytecode = 21) {
    dependencies {
        common {
            commonCoroutines()
            commonSerialization(protobuf = false)
        }
        jvm { api(Reaktor.Tooling) }
    }
    val unit by tests(Suite.Jvm, environment = ReaktorTests.Conductor)
}
