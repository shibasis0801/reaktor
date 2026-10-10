package dev.shibasis.dependeasy.server

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.springframework.boot.gradle.tasks.bundling.BootBuildImage
import org.springframework.boot.buildpack.platform.build.PullPolicy

internal fun Project.springDefaults() {
    listOf("implementation", "annotationProcessor", "testImplementation", "testRuntimeOnly", "developmentOnly").forEach {
        dependencies.add(it, dependencies.platform(Versions.Server.SpringBootBom))
    }
    listOf(Versions.Kotlin.SerializationCore, Versions.Kotlin.SerializationJson, Versions.Kotlin.SerializationProtobuf,
        Versions.Kotlin.SerializationCoreJvm, Versions.Kotlin.SerializationJsonJvm, Versions.Kotlin.SerializationProtobufJvm
    ).forEach { library ->
        dependencies.constraints.add("implementation", library) {
            version { strictly(Versions.Serialization) }
        }
    }
    configurations.named("compileOnly") { extendsFrom(configurations.getByName("annotationProcessor")) }
    configurations.configureEach { exclude(mapOf("group" to "org.jetbrains.compose.runtime")) }
    extensions.configure<KotlinJvmProjectExtension> { compilerOptions.freeCompilerArgs.add("-Xjsr305=strict") }
    tasks.withType<Jar>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

internal fun Project.springImage(image: String, configuration: BootBuildImage.() -> Unit) =
    tasks.named("bootBuildImage", BootBuildImage::class.java) {
        builder.set(ToolchainVersions.BuildpackBuilder)
        runImage.set(ToolchainVersions.BuildpackRunImage)
        pullPolicy.set(PullPolicy.IF_NOT_PRESENT)
        imagePlatform.set("linux/amd64")
        environment.set(mapOf("BP_NATIVE_IMAGE" to "false", "BP_JVM_VERSION" to Versions.SDK.Java.asString,
            "BP_JVM_TYPE" to "JRE", "JAVA_TOOL_OPTIONS" to
                "-XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:MaxMetaspaceSize=256m"))
        imageName.set(image)
        publish.set(false)
        configuration()
    }
