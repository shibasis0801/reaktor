import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.jvm") }

dependeasy {
    jvm(bytecode = 21) {
        dependencies {
            implementation(project(":reaktor-core"))
            implementation(Versions.Tooling.KotlinPoet)
            implementation(Versions.Tooling.KotlinPoetKsp)
            implementation(Versions.Tooling.KspApi)
            testImplementation(kotlin("test"))
        }
    }
}
