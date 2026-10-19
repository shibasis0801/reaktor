package dev.shibasis.reaktor.code

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class KotlinAnalysisIntegrationTest {
    @Test fun realAnalysisResolvesCommonJvmOverloadsAndShadowedLocalsThroughTheSourceGraph() {
        val root = Files.createTempDirectory("code-kotlin-analysis-").toRealPath()
        try {
            val helper = root.resolve("helper")
            ZipFile(System.getProperty("reaktor.kotlin.archive")).use { archive -> archive.entries().asSequence().forEach { entry ->
                val destination = helper.resolve(entry.name).normalize()
                require(destination.startsWith(helper))
                if (entry.isDirectory) Files.createDirectories(destination) else {
                    Files.createDirectories(destination.parent)
                    archive.getInputStream(entry).use { Files.copy(it, destination) }
                }
            } }
            Files.writeString(root.resolve("Api.kt"), """
                package fixture
                fun compute(value: Int): Int = value + 1
                fun compute(value: String): String = value + "!"
                expect fun platform(): String
            """.trimIndent() + "\r\n")
            Files.writeString(root.resolve("Use.kt"), """
                package fixture
                actual fun platform(): String = "jvm"
                fun run(): String {
                    val result = compute(1)
                    fun nested(): Int { val result = compute(2); return result }
                    return compute("🚀") + result + nested() + platform()
                }
            """.trimIndent().replace("\n", "\r\n") + "\r\n")
            val library = root.resolve("stdlib.jar")
            Files.copy(Path.of(System.getProperty("reaktor.kotlin.stdlib")), library)
            val commonLibrary = root.resolve("stdlib-common.klib")
            ZipFile(System.getProperty("reaktor.kotlin.commonStdlib")).use { archive ->
                ZipOutputStream(Files.newOutputStream(commonLibrary)).use { output ->
                    archive.entries().asSequence().filter { it.name.startsWith("commonMain/") && !it.isDirectory }.forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name.removePrefix("commonMain/")))
                        archive.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            val target = buildJsonObject {
                put("format", "reaktor-kotlin-target-v1"); put("compiler", "kotlin/2.4.21")
                put("languageVersion", "2.4"); put("apiVersion", "2.4"); put("jvmTarget", "25")
                put("jdkHome", Path.of(System.getProperty("java.home")).toRealPath().toString())
                putJsonArray("modules") {
                    addJsonObject { put("name", "commonMain"); put("platform", "common")
                        putJsonArray("sources") { add("Api.kt") }; putJsonArray("classpath") { add(commonLibrary.toString()) } }
                    addJsonObject { put("name", "jvmMain"); put("platform", "jvm")
                        putJsonArray("sources") { add("Use.kt") }; putJsonArray("classpath") { add(library.toString()) }
                        putJsonArray("dependsOn") { add("commonMain") } }
                }
            }
            Files.writeString(root.resolve("target.json"), target.toString())
            fun produce(): Int {
                val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.awt.headless=true", "-cp", "$helper/classes:$helper/lib/*", "dev.shibasis.reaktor.code.kotlinanalysis.MainKt",
                    root.toString(), "target.json", root.resolve("index.json").toString())
                    .redirectErrorStream(true).redirectOutput(root.resolve("producer.log").toFile()).start()
                if (!process.waitFor(90, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
                    fail("Producer exceeded fixture budget: ${Files.readString(root.resolve("producer.log"))}")
                }
                return process.exitValue()
            }
            assertEquals(0, produce(), Files.readString(root.resolve("producer.log")))
            val code = CodeWorkspace(root)
            val index = code.index("index.json")
            assertEquals("kotlin-analysis-api/2.5.0-Beta2-59", index.provider)
            assertEquals("kotlin-analysis/2.5.0-Beta2-59 (target kotlin/2.4.21, language/api 2.4, JVM 25)", index.compiler)
            val overloads = index.symbols("compute")
            assertEquals(2, overloads.total)
            val intFunction = overloads.candidates.single { it.span.start.line == 1 }
            val intUses = index.investigate(intFunction.identity)
            assertEquals(1, intUses.definitions)
            assertEquals(2, intUses.references)
            assertEquals(mapOf("Api.kt" to 1, "Use.kt" to 2), intUses.files)
            assertEquals(intUses.result.symbol, index.investigate("Use.kt", CodePosition(3, 20)).result.symbol)
            val stringUses = index.investigate(overloads.candidates.single { it.span.start.line == 2 }.identity)
            assertEquals(1, stringUses.references)
            assertEquals(2, index.symbols("result").total)
            index.symbols("result").candidates.forEach { assertEquals(2, index.investigate(it.identity).result.total) }
            val graph = index.sourceGraph(intUses.result)
            assertEquals(1, graph.wires.count { it.type == "code.definition" })
            assertEquals(2, graph.wires.count { it.type == "code.reference" })
            assertTrue(graph.wires.all { it in index.graph.wires })
            assertSame(index, code.index("index.json"))
            assertFailsWith<IllegalArgumentException> { code.renameLocal("index.json", "Use.kt", CodePosition(3, 8), "computed") }
            Files.write(library, byteArrayOf(1, 2, 3))
            assertFailsWith<IllegalArgumentException> { code.index("index.json") }
            Files.copy(Path.of(System.getProperty("reaktor.kotlin.stdlib")), library, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertSame(index, code.index("index.json"))
            Files.writeString(root.resolve("Use.kt"), "package fixture\nfun broken() = missingSymbol()\n")
            assertFailsWith<IllegalArgumentException> { code.index("index.json") }
            val oldArtifact = Files.readString(root.resolve("index.json"))
            assertNotEquals(0, produce())
            assertEquals(oldArtifact, Files.readString(root.resolve("index.json")))
            Files.writeString(root.resolve("target.json"), JsonObject(target + ("compilerPlugins" to JsonArray(emptyList()))).toString())
            assertNotEquals(0, produce())
            assertTrue(Files.readString(root.resolve("producer.log")).contains("Unknown target option"))
        } finally { Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun aServedSessionResolvesEachUseAcrossModulesFollowsOverridesAndStaysWarmUntilASourceChanges() {
        val root = Files.createTempDirectory("code-kotlin-serve-").toRealPath()
        try {
            val helper = unpack(root.resolve("helper"))
            val api = "package lib\n\nopen class Service {\n    open fun answer(): Int = 42\n}\n\nclass Other {\n    fun answer(): Int = 1\n}\n"
            val use = "package app\n\nimport lib.Other\nimport lib.Service\n\nclass Special : Service() {\n    override fun answer(): Int = 7\n}\n\n" +
                "fun use(service: Service, other: Other) = service.answer() + other.answer() + Special().answer()\n"
            Files.createDirectories(root.resolve("lib"))
            Files.createDirectories(root.resolve("app"))
            Files.writeString(root.resolve("lib/Api.kt"), api)
            Files.writeString(root.resolve("app/Use.kt"), use)
            fun at(path: String, line: Int, column: Int) = buildJsonObject { put("path", path); put("line", line); put("column", column) }
            fun places(path: String, text: String) = Regex("\\banswer\\b").findAll(text).map { match ->
                val before = text.substring(0, match.range.first)
                at(path, before.count { it == '\n' }, match.range.first - before.lastIndexOf('\n') - 1)
            }.toList()
            val stdlib = System.getProperty("reaktor.kotlin.stdlib")
            fun request(id: Int, current: String) = buildJsonObject {
                put("id", id)
                put("jdkHome", Path.of(System.getProperty("java.home")).toRealPath().toString())
                putJsonArray("modules") {
                    addJsonObject { put("name", "lib"); put("platform", "jvm"); putJsonArray("sources") { add("lib/Api.kt") }; putJsonArray("classpath") { add(stdlib) } }
                    addJsonObject {
                        put("name", "app"); put("platform", "jvm"); putJsonArray("sources") { add("app/Use.kt") }; putJsonArray("classpath") { add(stdlib) }
                        putJsonArray("dependencies") { add("lib") }
                    }
                }
                put("declaration", at("lib/Api.kt", 3, 13))
                put("candidates", JsonArray(places("lib/Api.kt", api) + places("app/Use.kt", current)))
            }
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Djava.awt.headless=true",
                "-cp", "$helper/classes:$helper/lib/*", "dev.shibasis.reaktor.code.kotlinanalysis.MainKt", "serve", root.toString())
                .redirectError(root.resolve("serve.log").toFile()).start()
            try {
                val input = process.outputStream.bufferedWriter()
                val output = process.inputStream.bufferedReader()
                fun ask(id: Int, current: String = use): JsonObject {
                    input.write(request(id, current).toString())
                    input.newLine()
                    input.flush()
                    return Json.parseToJsonElement(output.readLine() ?: fail(Files.readString(root.resolve("serve.log")))).jsonObject
                }
                fun verdicts(reply: JsonObject) = reply.getValue("decisions").jsonArray.map { it.jsonObject.getValue("verdict").jsonPrimitive.content }
                val first = ask(1)
                assertNull(first["error"], "$first")
                assertEquals(1, first.getValue("id").jsonPrimitive.int)
                assertEquals("lib/Api.kt:3:13", first.getValue("target").jsonPrimitive.content)
                assertEquals(setOf("lib/Api.kt:3:13", "app/Use.kt:6:17"), first.getValue("family").jsonArray.map { it.jsonPrimitive.content }.toSet())
                assertTrue(first.getValue("external").jsonArray.isEmpty())
                assertEquals(listOf("target", "other", "target", "target", "other", "target"), verdicts(first))
                assertFalse(first.getValue("warm").jsonPrimitive.boolean)
                val second = ask(2)
                assertTrue(second.getValue("warm").jsonPrimitive.boolean)
                assertEquals(verdicts(first), verdicts(second))
                val changed = use.replace("other.answer()", "other.answer() + 0")
                Files.writeString(root.resolve("app/Use.kt"), changed)
                val third = ask(3, changed)
                assertFalse(third.getValue("warm").jsonPrimitive.boolean)
                assertEquals(verdicts(first), verdicts(third))
            } finally {
                process.destroy()
                process.waitFor(10, TimeUnit.SECONDS)
            }
        } finally { Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    private fun unpack(helper: Path): Path {
        ZipFile(System.getProperty("reaktor.kotlin.archive")).use { archive -> archive.entries().asSequence().forEach { entry ->
            val destination = helper.resolve(entry.name).normalize()
            require(destination.startsWith(helper))
            if (entry.isDirectory) Files.createDirectories(destination) else {
                Files.createDirectories(destination.parent)
                archive.getInputStream(entry).use { Files.copy(it, destination) }
            }
        } }
        return helper
    }
}
