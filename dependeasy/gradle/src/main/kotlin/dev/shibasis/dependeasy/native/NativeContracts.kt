package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.model.NativeContract
import kotlinx.serialization.json.*
import java.io.File

internal fun NativeContract.cmake(): String {
    require(schema == 1) { "Unsupported native contract" }
    fun quote(value: String): String {
        require('\n' !in value && '\r' !in value && '\u0000' !in value) { "Invalid CMake value" }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
    }
    fun variable(name: String): String {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid CMake variable: $name" }
        return name
    }
    return buildString {
        fun setting(name: String, value: String) { append("set(${variable(name)} ${quote(value)} CACHE STRING \"\" FORCE)\n") }
        setting("DEPE_NATIVE_SCHEMA", schema.toString())
        setting("DEPE_NATIVE_TARGET", library)
        settings.toSortedMap().forEach { (name, value) -> setting(name, value) }
        setting("DEPE_NATIVE_INCLUDE_DIRECTORIES", includeDirectories.joinToString(";"))
        setting("DEPE_NATIVE_LINK_LIBRARIES", linkLibraries.joinToString(";"))
        setting("DEPE_NATIVE_COMPILE_OPTIONS", compileOptions.joinToString(";"))
        setting("DEPE_NATIVE_LINK_OPTIONS", linkOptions.joinToString(";"))
        append("if(DEPE_NATIVE_CONFIGURE_TARGETS)\n")
        imports.forEach { library ->
            require(library.kind in setOf("STATIC", "SHARED")) { "Unsupported native library kind" }
            append("  if(NOT TARGET ${quote(library.target)})\n")
            append("    add_library(${quote(library.target)} ${library.kind} IMPORTED GLOBAL)\n")
            append("    set_target_properties(${quote(library.target)} PROPERTIES IMPORTED_LOCATION ${quote(library.artifact)} INTERFACE_INCLUDE_DIRECTORIES ${quote(library.includes.joinToString(";"))} INTERFACE_LINK_LIBRARIES ${quote(library.links.joinToString(";"))})\n  endif()\n")
        }
        sources.filter { it.recipe == "hermes" }.forEach { source ->
            if (settings["ANDROID"] == "true") {
                append("  if(NOT TARGET jsi)\n    add_library(jsi SHARED ${quote(File(source.directory, "API/jsi/jsi/jsi.cpp").path)})\n")
                append("    target_include_directories(jsi PUBLIC ${quote(File(source.directory, "API/jsi").path)})\n  endif()\n")
            } else {
                append("  if(NOT TARGET hermesvm)\n")
                listOf("HERMES_ENABLE_TEST_SUITE", "HERMES_ENABLE_TOOLS", "HERMES_ENABLE_NAPI", "HERMES_ENABLE_DEBUGGER", "HERMES_ENABLE_INTL", "HERMES_BUILD_APPLE_FRAMEWORK", "HERMES_BUILD_SHARED_JSI", "BUILD_SHARED_LIBS")
                    .forEach { append("    set($it OFF CACHE BOOL \"\" FORCE)\n") }
                append("    set(IMPORT_HOST_COMPILERS ${quote(source.hostCompilers)} CACHE FILEPATH \"\" FORCE)\n")
                append("    add_subdirectory(${quote(source.directory)} \"\${CMAKE_CURRENT_BINARY_DIR}/deps/hermes\")\n  endif()\n")
            }
        }
        append("endif()\n")
    }
}

internal fun prefabLibrary(directory: File, abi: String, target: String): NativeContract.Library {
    val root = directory.resolve("libs/android.$abi")
    val metadata = Json.parseToJsonElement(root.resolve("abi.json").readText()).jsonObject
    require(metadata.getValue("abi").jsonPrimitive.content == abi) { "Prefab ABI mismatch" }
    val kind = if (metadata["static"]?.jsonPrimitive?.boolean == true) "STATIC" else "SHARED"
    val suffix = if (kind == "STATIC") "a" else "so"
    val artifact = root.listFiles().orEmpty().single { it.isFile && it.extension == suffix }
    return NativeContract.Library(target, kind, artifact.absolutePath, listOf(directory.resolve("include").absolutePath),
        if (target == "OpenSSL::SSL") listOf("OpenSSL::Crypto") else emptyList())
}
