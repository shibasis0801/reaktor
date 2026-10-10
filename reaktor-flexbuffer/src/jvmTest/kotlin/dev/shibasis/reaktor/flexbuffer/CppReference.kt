package dev.shibasis.reaktor.flexbuffer

import java.io.File

internal object CppReference {
    val binary: File
        get() = File(requireNotNull(System.getProperty("flexbuffer.reference")) {
            "Run the reference tests through Gradle so CMake builds their executable."
        }).also { check(it.canExecute()) { "Missing C++ reference executable: $it" } }
}
