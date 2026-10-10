import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.jvm") }

dependeasy {
    jvm(bytecode = 21) {
        protobuf("idb.proto")
        dependencies {
            implementation(Versions.Kotlin.CoroutinesCore)
            testImplementation(kotlin("test"))
        }
    }
}
