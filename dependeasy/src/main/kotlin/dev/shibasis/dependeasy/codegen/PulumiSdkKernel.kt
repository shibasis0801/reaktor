package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.dag.BuildPipeline
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

internal fun Project.pulumiSdk(name: String, version: String, server: String) = run {
    val prefix = name.replaceFirstChar(Char::uppercaseChar)
    val generate = tasks.register<PulumiSdkTask>("gen${prefix}Sdk") {
        providerName.set(name)
        providerVersion.set(version)
        this.server.set(server)
        executable.set("pulumi")
        toolVersion.set(providers.exec { commandLine("pulumi", "version") }.standardOutput.asText.map(String::trim))
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
    }
    val jar = tasks.register<Jar>("${name}SdkJar") {
        archiveBaseName.set("$name-sdk")
        from(compile.flatMap { it.destinationDirectory })
        from(generate.flatMap { it.outputDirectory }.map { it.dir("resources") })
    }
    BuildPipeline(this, "${name}Sdk").apply {
        target("build${prefix}Sdk", node(jar).after(node(compile).after(node(generate))))
        report()
    }
    jar
}
