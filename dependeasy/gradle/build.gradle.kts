import dev.shibasis.dependeasy.bootstrap.publishBootstrapOutputs
import dev.shibasis.dependeasy.toolchain.BuildLibraries

plugins { `kotlin-dsl`; `maven-publish` }

val fixtureToolchain = configurations.create("fixtureToolchain")
repositories.maven(BuildLibraries.GradleToolingRepository) { content { includeModule("org.gradle", "gradle-tooling-api") } }

dependencies {
    compileOnly(project(path = projects.toolchain.path, configuration = "runtimeElements"))
    implementation(projects.model)
    implementation("org.gradle:gradle-tooling-api:${gradle.gradleVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${BuildLibraries.Serialization}")
    implementation("org.yaml:snakeyaml:${BuildLibraries.SnakeYaml}")
    testImplementation(project(path = projects.toolchain.path, configuration = "runtimeElements"))
    fixtureToolchain(project(path = projects.toolchain.path, configuration = "runtimeElements"))
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
        "workspace" to "WorkspacePlugin",
        "library" to "LibraryPlugin",
        "application" to "ApplicationPlugin",
        "web-app" to "WebAppPlugin",
        "load-tests" to "LoadTestsPlugin",
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

publishBootstrapOutputs()
