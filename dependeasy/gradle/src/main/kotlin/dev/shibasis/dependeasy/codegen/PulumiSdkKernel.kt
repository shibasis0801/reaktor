package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.NodeKind
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.toolchain.pulumiTool
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

internal fun Project.pulumiSdk(name: String, version: String, server: String) = run {
    val prefix = name.replaceFirstChar(Char::uppercaseChar)
    val tool = pulumiTool()
    val generate = tasks.register<PulumiSdkTask>("gen${prefix}Sdk") {
        dependsOn(tool)
        providerName.set(name)
        providerVersion.set(version)
        this.server.set(server)
        executable.set(tool.map { it.destination.get().file("pulumi").asFile.absolutePath })
        toolVersion.set(ToolchainVersions.Pulumi)
        outputDirectory.set(layout.buildDirectory.dir("dependeasy/$name-sdk"))
    }
    val classpath = configurations.create("${name}SdkClasspath") { isCanBeConsumed = false }
    listOf(Versions.Server.Pulumi, Versions.Data.Gson, Versions.Tooling.Jsr305).forEach {
        dependencies.add(classpath.name, it)
    }
    val compiler = extensions.getByType<JavaToolchainService>().compilerFor {
        languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
    }
    val compile = tasks.register<JavaCompile>("compile${prefix}Sdk") {
        source(generate.flatMap { it.outputDirectory }.map { it.dir("java/src/main/java") })
        this.classpath = classpath
        javaCompiler.set(compiler)
        destinationDirectory.set(layout.buildDirectory.dir("dependeasy/$name-sdk-classes"))
        options.release.set(Versions.SDK.AndroidJava.asInt)
        options.encoding = "UTF-8"
        options.isFork = true
        options.forkOptions.memoryMaximumSize = "1024m"
        options.forkOptions.jvmArgs = listOf(
            "-XX:MaxMetaspaceSize=256m", "-XX:ReservedCodeCacheSize=32m", "-XX:+UseSerialGC"
        )
    }
    val jar = tasks.register<Jar>("${name}SdkJar") {
        archiveBaseName.set("$name-sdk")
        from(compile.flatMap { it.destinationDirectory })
        from(generate.flatMap { it.outputDirectory }.map { it.dir("resources") })
    }
    BuildWorkspace.get(this).aggregate(this, "build${prefix}Sdk", NodeKind.ArtifactSet,
        listOf(node(jar).after(node(compile).after(node(generate).after(rootProject.node(tool))))))
    jar
}
