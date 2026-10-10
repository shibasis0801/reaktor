package dev.shibasis.dependeasy.configuration

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.util.Properties

internal fun Project.localProperty(name: String): Provider<String> {
    val local = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orElse("").map { text -> Properties().apply { load(text.reader()) }.getProperty(name).orEmpty() }
    return providers.gradleProperty(name).orElse(local)
}
