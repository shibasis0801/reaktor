package dev.shibasis.dependeasy.dependencies

internal fun String.atVersion(version: String) = "${substringBeforeLast(':')}:$version"
