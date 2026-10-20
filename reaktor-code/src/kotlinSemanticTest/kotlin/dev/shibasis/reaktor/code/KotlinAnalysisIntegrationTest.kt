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

    @Test fun servedFactsNameEachDeclarationByItsCompilerAddressAndResolveEveryUseAndCall() {
        val root = Files.createTempDirectory("code-kotlin-facts-").toRealPath()
        try {
            val helper = unpack(root.resolve("helper"))
            val api = "package lib\n\nopen class Service(val name: String) {\n    open fun answer(): Int = 42\n}\n\nfun Service.twice(times: Int): Int = answer() * times\n\n" +
                "infix fun Service.join(other: Service): Int = 1\n\noperator fun Service.plus(other: Service): Service = this\n"
            val pair = "fun pair(service: Service): Int = (service + service join Special()) + missing"
            val use = "package app\n\nimport lib.Service\nimport lib.join\nimport lib.plus\nimport lib.twice\n\nclass Special : Service(\"special\") {\n    override fun answer(): Int = 7\n}\n\n" +
                "fun use(service: Service): Int = service.twice(2) + Special().answer()\n\n$pair\n"
            Files.createDirectories(root.resolve("lib"))
            Files.createDirectories(root.resolve("app"))
            Files.writeString(root.resolve("lib/Api.kt"), api)
            Files.writeString(root.resolve("app/Use.kt"), use)
            val stdlib = System.getProperty("reaktor.kotlin.stdlib")
            fun request(id: Int) = buildJsonObject {
                put("id", id); put("op", "facts")
                put("jdkHome", Path.of(System.getProperty("java.home")).toRealPath().toString())
                putJsonArray("modules") {
                    addJsonObject { put("name", "lib"); put("platform", "jvm"); putJsonArray("sources") { add("lib/Api.kt") }; putJsonArray("classpath") { add(stdlib) } }
                    addJsonObject {
                        put("name", "app"); put("platform", "jvm"); putJsonArray("sources") { add("app/Use.kt") }; putJsonArray("classpath") { add(stdlib) }
                        putJsonArray("dependencies") { add("lib") }
                    }
                }
            }
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Djava.awt.headless=true",
                "-cp", "$helper/classes:$helper/lib/*", "dev.shibasis.reaktor.code.kotlinanalysis.MainKt", "serve", root.toString())
                .redirectError(root.resolve("facts.log").toFile()).start()
            try {
                val input = process.outputStream.bufferedWriter()
                val output = process.inputStream.bufferedReader()
                fun ask(id: Int): JsonObject {
                    input.write(request(id).toString())
                    input.newLine()
                    input.flush()
                    return Json.parseToJsonElement(output.readLine() ?: fail(Files.readString(root.resolve("facts.log")))).jsonObject
                }
                val reply = ask(1)
                assertNull(reply["error"], "$reply")
                val addresses = reply.getValue("addresses").jsonArray.map { it.jsonPrimitive.content }
                fun file(reply: JsonObject, path: String) = reply.getValue("files").jsonArray.map { it.jsonObject }.single { it.getValue("path").jsonPrimitive.content == path }
                fun declared(reply: JsonObject, path: String) =
                    file(reply, path).getValue("declarations").jsonArray.map { it.jsonObject }.associateBy { it.getValue("n").jsonPrimitive.content }
                fun JsonObject.address(key: String = "a") = addresses[getValue(key).jsonPrimitive.int]
                fun JsonObject.addresses(key: String) = get(key)?.jsonArray.orEmpty().map { addresses[it.jsonPrimitive.int] }
                val lib = declared(reply, "lib/Api.kt")
                assertEquals("kotlin:lib/Service@lib", lib.getValue("Service").address())
                assertEquals("kotlin:lib/Service#name@lib", lib.getValue("name").address(), "a constructor property is named as the property")
                assertEquals("kotlin:lib/Service#answer()@lib", lib.getValue("answer").address())
                assertEquals("kotlin:lib/#twice[lib.Service](kotlin.Int)@lib", lib.getValue("twice").address())
                assertEquals("Int", lib.getValue("twice").getValue("t").jsonPrimitive.content)
                assertEquals("kotlin:lib/Service@lib", lib.getValue("answer").address("p"), "a member names the class it sits in")
                val app = declared(reply, "app/Use.kt")
                assertEquals(listOf("kotlin:lib/Service@lib"), app.getValue("Special").addresses("s"))
                assertEquals(listOf("kotlin:lib/Service#answer()@lib"), app.getValue("answer").addresses("o"))
                val references = file(reply, "app/Use.kt").getValue("references").jsonArray.map { it.jsonObject }
                fun flagged(flag: Int) = references.filter { ((it["f"]?.jsonPrimitive?.int ?: 0) and flag) != 0 }.flatMap { it.addresses("t") }
                val join = "kotlin:lib/#join[lib.Service](lib.Service)@lib"
                val plus = "kotlin:lib/#plus[lib.Service](lib.Service)@lib"
                assertEquals(setOf("kotlin:lib/#twice[lib.Service](kotlin.Int)@lib", "kotlin:app/Special#<init>()@app", "kotlin:app/Special#answer()@app", join, plus), flagged(1).toSet(),
                    "an infix call and an operator are calls too")
                assertEquals(setOf(plus), flagged(8).toSet(), "an operator is marked, because its sign is not the function's name")
                val line = use.split('\n').indexOf(pair)
                assertEquals(listOf(line to pair.indexOf("missing")), references.filter { ((it["f"]?.jsonPrimitive?.int ?: 0) and 4) != 0 }
                    .map { it.getValue("l").jsonPrimitive.int to it.getValue("c").jsonPrimitive.int }, "a name the compiler cannot resolve is kept and marked")
                assertTrue("kotlin:lib/#twice[lib.Service](kotlin.Int)@lib" in flagged(2), "the import of twice is a use too")
                assertTrue(references.filter { "kotlin:lib/#twice[lib.Service](kotlin.Int)@lib" in it.addresses("t") && ((it["f"]?.jsonPrimitive?.int ?: 0) and 1) != 0 }
                    .all { it.address("p") == "kotlin:app/#use(lib.Service)@app" }, "a call names the declaration it sits in")

                Files.writeString(root.resolve("lib/Api.kt"), "// moved down a line\n$api")
                val moved = ask(2)
                val again = moved.getValue("addresses").jsonArray.map { it.jsonPrimitive.content }
                val answer = declared(moved, "lib/Api.kt").getValue("answer")
                assertEquals("kotlin:lib/Service#answer()@lib", again[answer.getValue("a").jsonPrimitive.int], "the address does not move with the text")
                assertEquals(4, answer.getValue("l").jsonPrimitive.int)
            } finally {
                process.destroy()
                process.waitFor(10, TimeUnit.SECONDS)
            }
        } finally { Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }

    @Test fun servedJsAndNativeSessionsResolveUsesAgainstTheirKlibs() {
        val root = Files.createTempDirectory("code-kotlin-klibs-").toRealPath()
        try {
            val helper = unpack(root.resolve("helper"))
            val jvmStdlib = Path.of(System.getProperty("reaktor.kotlin.stdlib"))
            val version = jvmStdlib.fileName.toString().removePrefix("kotlin-stdlib-").removeSuffix(".jar")
            val jsStdlib = Files.walk(jvmStdlib.parent.parent.parent.parent.resolve("kotlin-stdlib-js/$version")).use { found ->
                found.filter { it.fileName.toString() == "kotlin-stdlib-js-$version.klib" }.findFirst().orElse(null)
            }
            assertNotNull(jsStdlib, "This test reads kotlin-stdlib-js-$version.klib from the Gradle cache next to ${jvmStdlib.fileName}")
            val konan = Files.list(Path.of(System.getProperty("user.home"), ".konan")).use { found ->
                found.filter { it.fileName.toString().startsWith("kotlin-native-prebuilt-") && it.fileName.toString().endsWith("-$version") }.findFirst().orElse(null)
            }
            assertNotNull(konan, "This test reads the Kotlin/Native $version distribution from ~/.konan")
            val platform = konan.resolve("klib/platform/ios_arm64")
            val libraries = linkedSetOf<Path>()
            val next = ArrayDeque(listOf("org.jetbrains.kotlin.native.platform.Foundation"))
            while (next.isNotEmpty()) {
                val library = platform.resolve(next.removeFirst())
                if (!libraries.add(library)) continue
                Files.readAllLines(library.resolve("default/manifest")).first { it.startsWith("depends=") }.removePrefix("depends=").split(' ')
                    .filter { it.isNotBlank() && it != "stdlib" }.forEach(next::addLast)
            }
            val common = "package demo\n\nfun greet(): String = \"hi\"\n"
            val js = "package demo\n\nfun shout(): Int = greet().length\n"
            val ios = "package demo\n\nimport platform.Foundation.NSData\n\nfun size(data: NSData): Int = data.length.toInt() + greet().length\n"
            listOf("common/Greeting.kt" to common, "js/Shout.kt" to js, "ios/Size.kt" to ios).forEach { (path, text) ->
                Files.createDirectories(root.resolve(path).parent)
                Files.writeString(root.resolve(path), text)
            }
            fun at(path: String, text: String, word: String, from: Int = 0): JsonObject {
                val offset = Regex("\\b$word\\b").find(text, from)!!.range.first
                val before = text.substring(0, offset)
                return buildJsonObject { put("path", path); put("line", before.count { it == '\n' }); put("column", offset - before.lastIndexOf('\n') - 1) }
            }
            fun request(id: Int, family: String, own: String, ownText: String, classpath: List<String>, more: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
                put("id", id)
                put("jdkHome", Path.of(System.getProperty("java.home")).toRealPath().toString())
                put("platform", family)
                more()
                putJsonArray("modules") {
                    addJsonObject { put("name", "common"); put("platform", family); putJsonArray("sources") { add("common/Greeting.kt") }; putJsonArray("classpath") { classpath.forEach { add(it) } } }
                    addJsonObject {
                        put("name", family); put("platform", family); putJsonArray("sources") { add(own) }; putJsonArray("classpath") { classpath.forEach { add(it) } }
                        putJsonArray("dependsOn") { add("common") }
                    }
                }
                put("declaration", at("common/Greeting.kt", common, "greet"))
                putJsonArray("candidates") {
                    add(at(own, ownText, "greet"))
                    add(at(own, ownText, "length", ownText.indexOf("=")))
                }
            }
            val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Djava.awt.headless=true",
                "-cp", "$helper/classes:$helper/lib/*", "dev.shibasis.reaktor.code.kotlinanalysis.MainKt", "serve", root.toString())
                .redirectError(root.resolve("serve.log").toFile()).start()
            try {
                val input = process.outputStream.bufferedWriter()
                val output = process.inputStream.bufferedReader()
                fun ask(request: JsonObject): JsonObject {
                    input.write(request.toString())
                    input.newLine()
                    input.flush()
                    return Json.parseToJsonElement(output.readLine() ?: fail(Files.readString(root.resolve("serve.log")))).jsonObject
                }
                fun verdicts(reply: JsonObject) = reply.getValue("decisions").jsonArray.map { it.jsonObject.getValue("verdict").jsonPrimitive.content }
                val onJs = ask(request(1, "js", "js/Shout.kt", js, listOf(jsStdlib.toString())))
                assertNull(onJs["error"], "$onJs")
                assertEquals(listOf("target", "other"), verdicts(onJs), "greet is the declaration and length is the JS stdlib's: $onJs")
                val onIos = ask(request(2, "native", "ios/Size.kt", ios, (listOf(konan.resolve("klib/common/stdlib")) + libraries).map { it.toString() }) {
                    put("konanTarget", "ios_arm64")
                })
                assertNull(onIos["error"], "$onIos")
                assertEquals(listOf("target", "other"), verdicts(onIos), "greet is the declaration and length is Foundation's NSData.length: $onIos")
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
