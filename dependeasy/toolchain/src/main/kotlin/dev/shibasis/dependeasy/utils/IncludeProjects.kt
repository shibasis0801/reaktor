package dev.shibasis.dependeasy.utils

import org.gradle.api.initialization.Settings
import java.io.File

fun Settings.includeWithPath(name: String, path: String? = null) {
    val newName = ":$name"
    include(newName)
    if (path != null) {
        project(newName).projectDir = File(path)
    }
}
