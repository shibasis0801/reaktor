package dev.shibasis.dependeasy.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class KotlinPackageKernelTest {
    private val prelude = "type Nullable<T> = T | null | undefined\ndeclare function KtSingleton<T>(): T & (abstract new() => any);"

    private fun library(build: File.() -> Unit): Pair<File, KernelSpec.KotlinPackage> {
        val root = createTempDirectory("kotlin-package").toFile()
        val library = root.resolve("library").apply { mkdirs(); build() }
        return library to KernelSpec.KotlinPackage(FileSet(emptyList()), FileSet(listOf(library.path)),
            FileSet(listOf(root.resolve("node_modules").path)), FileSet(listOf(root.resolve("dependencies.properties").path)))
    }

    private fun File.write(path: String, text: String) = resolve(path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun perFileOutputGetsOneRuntimeThatRunsEagerInitializersAndReexportsEveryLibrary() {
        val (library, spec) = library {
            write("package.json", """{"name":"app-kt","version":"1.0.0","main":"App.mjs","types":"App.d.mts"}""")
            write("App.mjs", "import './net.mjs';\n//region block: exports\nexport * from './App/app/Screen.export.mjs';\n//endregion\n")
            write("App/app/Screen.export.d.mts", "$prelude\nexport declare class Screen {\n    engine(): Engine;\n}")
            write("net.mjs", "import './net/net/Engine.mjs';\nexport * from './net/net/Engine.export.mjs';\n")
            write("net/net/Engine.export.d.mts", "type Nullable<T> = T | null | undefined\nexport declare class Engine {\n}")
            write("skiko.mjs", "export const awaitSkiko = null;\n")
        }

        KotlinPackageKernel.runtime(spec)

        assertEquals("import './net/net/Engine.mjs';\nexport * from './App.mjs';\nexport * from './net.mjs';\n", library.resolve("runtime.mjs").readText())
        assertEquals("$prelude\nexport declare class Screen {\n    engine(): Engine;\n}\nexport declare class Engine {\n}\n", library.resolve("runtime.d.mts").readText())
        val manifest = Json.parseToJsonElement(library.resolve("package.json").readText()).jsonObject
        assertEquals(listOf("app-kt", "runtime.mjs", "runtime.d.mts"), listOf("name", "main", "types").map { manifest.getValue(it).jsonPrimitive.content })
    }

    @Test
    fun wholeProgramOutputKeepsItsOwnEntry() {
        val text = """{"name":"app-kt","main":"App.mjs","types":"App.d.mts"}"""
        val (library, spec) = library {
            write("package.json", text)
            write("App.mjs", "export { Screen };\n")
        }

        KotlinPackageKernel.runtime(spec)

        assertFalse(library.resolve("runtime.mjs").exists())
        assertEquals(text, library.resolve("package.json").readText())
    }
}
