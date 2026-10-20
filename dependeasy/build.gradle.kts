import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.bootstrap.BootstrapLifecycle
import dev.shibasis.dependeasy.bootstrap.VerifyBytecode
import dev.shibasis.dependeasy.bootstrap.bootstrapAttribute
import dev.shibasis.dependeasy.publishing.PublicationDefaults
import dev.shibasis.dependeasy.toolchain.GradlePlugins
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

plugins {
    base
    `kotlin-dsl` apply false
}

group = "${PublicationDefaults.Group}.dependeasy"
version = option(BuildOption.string(PublicationDefaults.VersionOptionName, PublicationDefaults.Version)).get()

subprojects {
    group = rootProject.group
    version = rootProject.version
    repositories {
        google()
        mavenCentral()
        maven(url = "https://plugins.gradle.org/m2/")
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(GradlePlugins.CompilerJava)
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(GradlePlugins.BytecodeJava.toString()))
        }
        extensions.configure<JavaPluginExtension> {
            targetCompatibility = JavaVersion.toVersion(GradlePlugins.BytecodeJava)
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(GradlePlugins.BytecodeJava) }
    }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/${PublicationDefaults.Repository}")
                credentials {
                    username = project.option(BuildOption.environment("USERNAME")).orNull
                    password = project.option(BuildOption.environment("TOKEN", sensitive = true)).orNull
                }
            }
        }
    }
}

fun lifecycleInputs(lifecycle: BootstrapLifecycle, modules: List<Project> = subprojects.toList()): Configuration {
    val name = lifecycle.task
    return configurations.create("bootstrap${name.replaceFirstChar(Char::uppercase)}") {
        isCanBeConsumed = false
        isTransitive = false
        attributes.attribute(bootstrapAttribute, name)
        modules.forEach { dependencies.add(project.dependencies.project(mapOf("path" to it.path))) }
    }
}
val bytecodeJars = configurations.create("bootstrapJars") {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
    subprojects.forEach { dependencies.add(project.dependencies.project(mapOf("path" to it.path))) }
}
val verifyBytecode = tasks.register<VerifyBytecode>("verifyBytecode") { jars.from(bytecodeJars) }
val checks = lifecycleInputs(BootstrapLifecycle.Check)
val assembly = lifecycleInputs(BootstrapLifecycle.Assemble)
tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(verifyBytecode, checks) }
tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME) { dependsOn(assembly) }
val pluginTests = tasks.register(JavaPlugin.TEST_TASK_NAME) {
    val result = layout.buildDirectory.file("dependeasy/plugin-tests.txt")
    dependsOn(lifecycleInputs(BootstrapLifecycle.Test, listOf(project(projects.gradle.path))))
    outputs.file(result)
    doLast { result.get().asFile.apply { parentFile.mkdirs(); writeText("verified\n") } }
}
configurations.create("pluginVerification") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes.attribute(Attribute.of("dependeasy.output", String::class.java), "VerificationResults:Plugin")
    outgoing.artifact(layout.buildDirectory.file("dependeasy/plugin-tests.txt")) { builtBy(pluginTests) }
}
val localPublication = lifecycleInputs(BootstrapLifecycle.LocalPublication)
val remotePublication = lifecycleInputs(BootstrapLifecycle.RemotePublication)
tasks.register(MavenPublishPlugin.PUBLISH_LOCAL_LIFECYCLE_TASK_NAME) { dependsOn(localPublication) }
tasks.register(PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME) { dependsOn(remotePublication) }
