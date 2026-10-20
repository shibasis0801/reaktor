package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.model.FileSet

internal data class Profile(val sources: FileSet, val output: String, val outputEnvironment: String? = null)
