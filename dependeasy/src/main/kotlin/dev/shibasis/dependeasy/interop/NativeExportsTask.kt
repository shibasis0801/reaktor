package dev.shibasis.dependeasy.interop

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.*

/** Compile-time composition: no global mutable registry or platform handles in the public API. */
@CacheableTask
abstract class NativeExportsTask : DefaultTask() {
    @get:Input abstract val installers: MapProperty<String, String>
    @get:OutputFile abstract val header: RegularFileProperty

    @TaskAction fun generate() {
        val modules = installers.get().toSortedMap()
        modules.forEach { (include, install) ->
            require(include.matches(Regex("[A-Za-z0-9_./-]+"))) { "Invalid C++ module header: $include" }
            require(install.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(::[A-Za-z_][A-Za-z0-9_]*)*"))) { "Invalid C++ installer: $install" }
        }
        val output = header.get().asFile
        output.parentFile.mkdirs()
        output.writeText("#pragma once\n#include <reaktor/interop/Runtime.hpp>\n" +
            modules.keys.joinToString("") { "#include <$it>\n" } +
            "namespace reaktor::interop::generated {\ninline void install(Runtime& runtime) {\n" +
            modules.values.joinToString("") { "    $it(runtime);\n" } + "}\n}\n")
    }
}
