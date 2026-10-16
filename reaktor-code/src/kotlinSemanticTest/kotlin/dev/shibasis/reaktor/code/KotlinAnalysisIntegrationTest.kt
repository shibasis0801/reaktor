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
}
