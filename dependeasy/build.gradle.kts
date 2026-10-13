import dev.shibasis.dependeasy.bootstrap.VerifyBytecode
import dev.shibasis.dependeasy.publishing.PublicationDefaults
import dev.shibasis.dependeasy.toolchain.GradlePlugins
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.gradle.jvm.tasks.Jar

plugins {
    base
    `kotlin-dsl` apply false
}

group = "${PublicationDefaults.Group}.dependeasy"
version = providers.gradleProperty("reaktorVersion").orElse(PublicationDefaults.Version).get()

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
                    username = providers.environmentVariable("USERNAME").orNull
                    password = providers.environmentVariable("TOKEN").orNull
                }
            }
        }
    }
}

val verifyBytecode = tasks.register<VerifyBytecode>("verifyBytecode") {
    jars.from(subprojects.map { it.tasks.named<Jar>("jar").flatMap { jar -> jar.archiveFile } })
}
tasks.named("check") {
    dependsOn(verifyBytecode, subprojects.map { "${it.path}:check" })
}
tasks.named("assemble") { dependsOn(subprojects.map { "${it.path}:assemble" }) }
tasks.register("test") { dependsOn(project(":gradle").tasks.named("test")) }
tasks.register("publishToMavenLocal") { dependsOn(subprojects.map { "${it.path}:publishToMavenLocal" }) }
tasks.register("publish") { dependsOn(subprojects.map { "${it.path}:publish" }) }
