import dev.shibasis.dependeasy.configuration.FrameworkOptions
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.model.GeneratedObject
import dev.shibasis.dependeasy.model.Suite
import dev.shibasis.dependeasy.model.Targets

plugins {
    id("dev.shibasis.dependeasy.library")
}

val BuildKonfig by GeneratedObject("dev.shibasis.reaktor.core") {
    val SERVER by string(FrameworkOptions.ServerHost)
}

library(namespace = "dev.shibasis.reaktor.io", targets = Targets.All) {
    val generateBuildKonfig by generated(BuildKonfig)
    val unit by tests(Suite.Jvm)
    val androidUnit by tests(Suite.AndroidUnit)

    dependencies {
        common {
            api(projects.reaktorCore)
            api(Versions.Kotlin.Io)
            api(Versions.Kotlin.KtorCore)
            api(Versions.Kotlin.KtorContentNegotiation)
            api(Versions.Kotlin.KtorJson)
            api(Versions.Kotlin.KtorLogging)
        }
        commonTest { implementation(Versions.Kotlin.KtorMock) }
        web { api(Versions.Kotlin.KtorJs) }
        android { api(Versions.Data.OkHttp); api(Versions.Kotlin.KtorOkHttp) }
        apple { api(Versions.Kotlin.KtorDarwin) }
        jvm { api(Versions.Data.OkHttp); api(Versions.Kotlin.KtorOkHttp) }
        jvmTest { implementation(Versions.Data.MockWebServer) }
    }
}
