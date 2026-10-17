package dev.shibasis.dependeasy.verification

import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

fun Test.classpathProperty(name: String, classpath: FileCollection) {
    jvmArgumentProviders.add(TestClasspath(name, classpath))
}

private class TestClasspath(@get:Input val name: String, @get:Classpath val classpath: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-D$name=${classpath.asPath}")
}
