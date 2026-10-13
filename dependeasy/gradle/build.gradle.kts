import dev.shibasis.dependeasy.toolchain.BuildLibraries

plugins { `kotlin-dsl`; `maven-publish` }

kotlin.sourceSets.main { kotlin.exclude("dev/shibasis/dependeasy/toolchain/BuildLibraries.kt") }
val fixtureToolchain = configurations.create("fixtureToolchain")

dependencies {
    compileOnly(project(path = ":toolchain", configuration = "runtimeElements"))
    implementation(project(":model"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${BuildLibraries.Serialization}")
    implementation("org.yaml:snakeyaml:${BuildLibraries.SnakeYaml}")
    testImplementation(project(path = ":toolchain", configuration = "runtimeElements"))
    fixtureToolchain(project(path = ":toolchain", configuration = "runtimeElements"))
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5", embeddedKotlinVersion))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    mapOf(
        "compose-library" to "ComposeLibraryPlugin",
        "compose-application" to "ComposeApplicationPlugin",
        "browser" to "BrowserPlugin",
        "compose-jvm" to "ComposeJvmPlugin",
        "jvm" to "JvmPlugin",
        "spring-application" to "SpringApplicationPlugin",
        "pipeline" to "PipelinePlugin",
        "library" to "LibraryPlugin",
        "application" to "ApplicationPlugin",
    ).forEach { (name, implementation) ->
        plugins.create(name) {
            id = "dev.shibasis.dependeasy.$name"
            implementationClass = "dev.shibasis.dependeasy.plugins.$implementation"
        }
    }
}

tasks.pluginUnderTestMetadata { pluginClasspath.from(fixtureToolchain) }
tasks.test {
    useJUnitPlatform()
    workingDir(rootDir)
    inputs.dir(rootProject.file("checks"))
}
