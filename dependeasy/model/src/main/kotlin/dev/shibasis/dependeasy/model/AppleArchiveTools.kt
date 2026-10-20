package dev.shibasis.dependeasy.model

import java.io.File

object AppleArchiveTools {
    val tools = listOf(ToolRef("plutil", "unversioned host tool"), ToolRef("xcrun/dwarfdump", "unversioned host tool"))
    fun plist(file: File) = listOf("/usr/bin/plutil", "-convert", "json", "-o", "-", file.absolutePath)
    fun uuids(file: File) = listOf("xcrun", "dwarfdump", "--uuid", file.absolutePath)
}
