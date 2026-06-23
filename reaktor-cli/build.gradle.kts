plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

dependencies {
    // Clikt 5 (typed composable subcommands) + Mordant 3 (rich terminal); same author.
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    // For reading the project's package.json "reaktor" key (runtime only; no @Serializable codegen needed).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

val cliRegressionTest by tasks.registering(JavaExec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs CLI regression tests without requiring an external test framework."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.shibasis.reaktor.cli.CliRegressionTestKt")
    dependsOn(tasks.named("testClasses"))
}

tasks.named("check") {
    dependsOn(cliRegressionTest)
}

application {
    applicationName = "reaktor"
    mainClass.set("dev.shibasis.reaktor.cli.MainKt")
    // Mordant uses JNA for terminal detection; silence the JDK 21 native-access warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
