import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.jvm") }

library(bytecode = 21) {
    dependencies {
        implementation(projects.reaktorCore)
        implementation(Versions.Tooling.KotlinPoet)
        implementation(Versions.Tooling.KotlinPoetKsp)
        implementation(Versions.Tooling.KspApi)
        testImplementation(kotlin("test"))
    }
}
