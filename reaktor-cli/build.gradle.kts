plugins {
    kotlin("jvm") version "2.3.0"
    application
}

repositories { mavenCentral() }

dependencies {
    // Clikt 5 (typed composable subcommands) + Mordant 3 (rich terminal); same author.
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    // For reading the project's package.json "reaktor" key (runtime only; no @Serializable codegen needed).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("dev.shibasis:reaktor-tooling:local")
}

val e2e by sourceSets.creating

configurations[e2e.implementationConfigurationName].extendsFrom(configurations["implementation"])
configurations[e2e.runtimeOnlyConfigurationName].extendsFrom(configurations["runtimeOnly"])
e2e.compileClasspath += sourceSets.main.get().output
e2e.runtimeClasspath += sourceSets.main.get().output

val toolingE2eSmoke by tasks.registering(JavaExec::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Discovers a fixture workspace and smoke-tests supervised success, failure, and cancellation."
    classpath = e2e.runtimeClasspath
    mainClass.set("dev.shibasis.reaktor.cli.ToolingE2eSmokeKt")
    dependsOn(tasks.named(e2e.classesTaskName))
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
    dependsOn(toolingE2eSmoke)
}

application {
    applicationName = "reaktor"
    mainClass.set("dev.shibasis.reaktor.cli.MainKt")
    // Mordant uses JNA for terminal detection; silence the JDK 21 native-access warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
