import dev.shibasis.dependeasy.Versions

plugins { id("dev.shibasis.dependeasy.jvm"); application }

dependeasy {
    jvm(bytecode = 21) {
        dependencies {
            // Clikt 5 (typed composable subcommands) + Mordant 3 (rich terminal); same author.
            implementation(Versions.Tooling.Clikt)
            implementation(Versions.Tooling.Mordant)
            // For reading the project's package.json "reaktor" key (runtime only; no @Serializable codegen needed).
            implementation(Versions.Kotlin.SerializationJson)
            implementation("dev.shibasis:reaktor-tooling:local")
            implementation("dev.shibasis:reaktor-devtools:local") {
                listOf("org.jetbrains.compose", "androidx.compose", "org.jetbrains.skiko").forEach { prefix ->
                    listOf("runtime", "ui", "foundation", "animation", "material", "material3", "annotation-internal", "collection-internal").forEach { exclude(group = "$prefix.$it") }
                }
                exclude(group = "org.jetbrains.skiko")
            }
        }
    }
    jvmEntryPoint("toolingE2eSmoke", "dev.shibasis.reaktor.cli.ToolingE2eSmokeKt", "e2e", verify = true)
    jvmEntryPoint("cliRegressionTest", "dev.shibasis.reaktor.cli.CliRegressionTestKt", "e2e", verify = true)
    application {
        applicationName = "reaktor"
        mainClass.set("dev.shibasis.reaktor.cli.MainKt")
        // Mordant uses JNA for terminal detection.
        applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
    }

    tasks.withType<JavaExec>().configureEach { jvmArgs(application.applicationDefaultJvmArgs) }
}
