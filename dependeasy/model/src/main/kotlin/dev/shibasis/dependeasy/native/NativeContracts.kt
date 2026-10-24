package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.model.NativeContract
import kotlinx.serialization.json.*
import java.io.File

fun NativeContract.cmake(): String {
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
        append("if(NOT DEPE_NATIVE_CONFIGURE_TARGETS)\n")
        setting("DEPE_NATIVE_SCHEMA", schema.toString())
        setting("DEPE_NATIVE_TARGET", library)
        setting("DEPE_NATIVE_TESTS", if (executables.any { it.discoverTests }) "ON" else "OFF")
        require(libraryKind in setOf("STATIC", "SHARED")) { "Unsupported native library kind" }
        setting("DEPE_NATIVE_LIBRARY_KIND", libraryKind)
        setting("DEPE_NATIVE_SOURCES", compilationSources.joinToString(";"))
        setting("DEPE_NATIVE_PRIVATE_INCLUDE_DIRECTORIES", privateIncludeDirectories.joinToString(";"))
        settings.toSortedMap().forEach { (name, value) -> setting(name, value) }
        sources.filter { it.recipe == "webview" }.singleOrNull()?.let { source ->
            setting("DEPE_WEBVIEW_SOURCE", source.directory)
            listOf("WEBVIEW_BUILD_TESTS", "WEBVIEW_BUILD_EXAMPLES", "WEBVIEW_BUILD_DOCS", "WEBVIEW_BUILD_AMALGAMATION",
                "WEBVIEW_BUILD_SHARED_LIBRARY", "WEBVIEW_INSTALL_TARGETS", "WEBVIEW_ENABLE_PACKAGING").forEach { setting(it, "OFF") }
            setting("WEBVIEW_BUILD_STATIC_LIBRARY", "ON")
            setting("WEBVIEW_MSWEBVIEW2_VERSION", settings.getValue("REAKTOR_WEBVIEW_WINDOWS_SDK"))
            setting("WEBVIEW_WEBKITGTK_API", settings.getValue("REAKTOR_WEBVIEW_WEBKITGTK_API"))
        }
        setting("DEPE_NATIVE_INCLUDE_DIRECTORIES", includeDirectories.joinToString(";"))
        setting("DEPE_NATIVE_LINK_LIBRARIES", linkLibraries.joinToString(";"))
        setting("DEPE_NATIVE_COMPILE_OPTIONS", compileOptions.joinToString(";"))
        setting("DEPE_NATIVE_LINK_OPTIONS", linkOptions.joinToString(";"))
        append("endif()\n")
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
        projects.forEach { project ->
            project.settings.toSortedMap().forEach { (name, value) -> setting(name, value) }
            append("  add_subdirectory(${quote(project.directory)} EXCLUDE_FROM_ALL)\n")
            project.packageVariable?.let { name ->
                append("  set(${variable(name)} \"\${CMAKE_CURRENT_BINARY_DIR}/${project.directory}\" CACHE PATH \"\" FORCE)\n")
            }
        }
        if (executables.any { it.discoverTests }) append("  include(GoogleTest)\n")
        require(executables.map { it.name }.distinct().size == executables.size) { "Duplicate native executable" }
        executables.forEach { executable ->
            require(executable.name.isNotBlank() && executable.sources.isNotEmpty()) { "Native executable needs a name and sources" }
            append("  add_executable(${quote(executable.name)} ${executable.sources.joinToString(" ", transform = ::quote)})\n")
            if (executable.discoverTests) append("  gtest_discover_tests(${quote(executable.name)})\n")
            if (executable.links.isNotEmpty()) append("  target_link_libraries(${quote(executable.name)} PRIVATE ${executable.links.joinToString(" ", transform = ::quote)})\n")
            if (executable.includes.isNotEmpty()) append("  target_include_directories(${quote(executable.name)} PRIVATE ${executable.includes.joinToString(" ", transform = ::quote)})\n")
        }
        append("endif()\n")
    }
}

fun prefabLibrary(directory: File, abi: String, declaration: AndroidPrefabConfiguration): NativeContract.Library {
    val root = directory.resolve("libs/android.$abi")
    val metadata = Json.parseToJsonElement(root.resolve("abi.json").readText()).jsonObject
    require(metadata.getValue("abi").jsonPrimitive.content == abi) { "Prefab ABI mismatch" }
    val kind = if (metadata["static"]?.jsonPrimitive?.boolean == true) "STATIC" else "SHARED"
    val suffix = if (kind == "STATIC") "a" else "so"
    val artifact = root.listFiles().orEmpty().single { it.isFile && it.extension == suffix }
    return NativeContract.Library(declaration.target, kind, artifact.absolutePath, listOf(directory.resolve("include").absolutePath), declaration.links)
}
