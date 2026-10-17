package dev.shibasis.dependeasy.module

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.JavaExec
import org.gradle.process.CommandLineArgumentProvider

fun JavaExec.arguments(values: Provider<List<String>>) {
    argumentProviders.add(ProviderArguments(values))
}

private class ProviderArguments(@get:Input val values: Provider<List<String>>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = values.get()
}
