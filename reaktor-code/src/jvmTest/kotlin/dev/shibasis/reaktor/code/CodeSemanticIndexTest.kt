package dev.shibasis.reaktor.code

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CodeSemanticIndexTest {
    @Test fun graphSymbolNamesDistinguishShadowedBindingsAndResolveOneOverloadIdentity() = fixture { _, code ->
        val index = code.index("index.json")
        val compute = index.symbols("compute")
        assertEquals(1, compute.total)
        assertEquals("api.ts", compute.candidates.single().path)
        assertTrue(compute.candidates.single().definition)
        val result = index.investigate(compute.candidates.single().identity)
        assertEquals(index.investigate("api.ts", CodePosition(0, 18)), result)
        assertEquals(3, result.definitions)
        assertEquals(3, result.references)
        val scoped = index.symbols("compute", "use.ts")
        assertEquals(1, scoped.total)
        assertFalse(scoped.candidates.single().definition)
        assertEquals(result, index.investigate(scoped.candidates.single().identity))
        val locals = index.symbols("result", limit = 1)
        assertEquals(2, locals.total)
        assertEquals(1, locals.candidates.size)
        val all = index.symbols("result")
        assertEquals(listOf(3, 6), all.candidates.map { it.span.start.line })
        assertEquals(2, all.candidates.map { it.identity }.distinct().size)
        all.candidates.forEach { candidate ->
            assertEquals(2, index.investigate(candidate.identity).result.total)
            val symbol = index.graph.nodes.single { it.type == "CodeSymbol" && it.label == candidate.identity }
            assertTrue(index.graph.wires.any { it.provider == symbol.id && it.type == "code.definition" })
        }
        assertEquals(0, index.symbols("computeLonger").total)
        assertFailsWith<IllegalArgumentException> { index.symbols("compute", "outside.ts") }
        assertFailsWith<IllegalArgumentException> { index.symbols("compute\n") }
        assertFailsWith<IllegalArgumentException> { index.investigate("unknown symbol") }
    }

    @Test fun investigationTraversesTheSameSourceGraphAndKeepsCompleteCountsWhenOccurrencesAreLimited() = fixture { _, code ->
        val index = code.index("index.json")
        val full = index.investigate("use.ts", CodePosition(1, 24))
        assertEquals("compute", full.selected.text)
        assertEquals(3, full.definitions)
        assertEquals(3, full.references)
        assertEquals(mapOf("api.ts" to 3, "use.ts" to 3), full.files)
        assertEquals(index.query("api.ts", CodePosition(0, 18), "impact").occurrences.toSet(), full.result.occurrences.toSet())
        val limited = index.investigate("use.ts", CodePosition(1, 24), 1)
        assertEquals(listOf(full.selected), limited.result.occurrences)
        assertEquals(full.files, limited.files)
        assertEquals(6, limited.result.total)
        assertTrue(limited.result.omissions.any { it.startsWith("5 occurrences") })
        val source = index.sourceGraph(full.result)
        assertTrue(source.scopes.none { it.active })
        assertEquals(3, source.wires.count { it.type == "code.definition" })
        assertEquals(3, source.wires.count { it.type == "code.reference" })
        assertTrue(source.nodes.all { it.definitionId.isNotEmpty() && it.lifecycle == null })
        assertTrue(source.nodes.all { shown -> index.graph.nodes.any { it.id == shown.id && it.definitionId == shown.definitionId && it.attributes == shown.attributes } })
        assertTrue(source.wires.all { it in index.graph.wires })
        source.wires.forEach { wire ->
            assertEquals(1, source.nodes.single { it.id == wire.consumer }.ports.count { !it.provides && it.key == wire.consumerPort && it.type == wire.type })
            assertEquals(1, source.nodes.single { it.id == wire.provider }.ports.count { it.provides && it.key == wire.providerPort && it.type == wire.type })
        }
        assertEquals(source, code.index("index.json").sourceGraph(full.result))
        val outer = index.investigate("api.ts", CodePosition(3, 28))
        assertEquals(1, outer.definitions)
        assertEquals(1, outer.references)
        assertEquals(2, outer.result.total)
        assertTrue(outer.result.occurrences.none { it.span.start.line == 6 })
    }

    @Test fun recordedReferenceRelationshipsUseTypedGraphEdgesAndBoundCyclicExpansion() = fixture { root, code ->
        val artifact = Json.parseToJsonElement(Files.readString(root.resolve("index.json"))).jsonObject
        val rawIndex = artifact.getValue("index").jsonObject
        val docs = rawIndex.getValue("documents").jsonArray
        val api = docs.first().jsonObject
        val symbols = api.getValue("symbols").jsonArray
        val addresses = symbols.map { it.jsonObject.getValue("symbol").jsonPrimitive.content }.distinct()
        val compute = addresses.single { it.endsWith("/compute().") }
        val shorthand = addresses.single { it.endsWith("/shorthand().") }
        val updated = JsonArray(symbols.map { raw ->
            val item = raw.jsonObject
            val other = when (item.getValue("symbol").jsonPrimitive.content) { compute -> shorthand; shorthand -> compute; else -> null }
            if (other == null) item else JsonObject(item + ("relationships" to buildJsonArray { addJsonObject { put("symbol", other); put("is_reference", true) } }))
        })
        Files.writeString(root.resolve("index.json"), JsonObject(artifact + ("index" to JsonObject(rawIndex + ("documents" to JsonArray(listOf(JsonObject(api + ("symbols" to updated))) + docs.drop(1)))))).toString())
        val index = code.index("index.json")
        val result = index.investigate("use.ts", CodePosition(1, 24))
        assertTrue(result.result.occurrences.any { it.text == "shorthand" && it.definition })
        val graph = index.sourceGraph(result.result)
        assertEquals(2, graph.wires.count { it.type == "code.symbol-reference" })
        graph.nodes.forEach { node -> node.ports.forEach { port ->
            assertEquals(port.connections, graph.wires.count { it.type == port.type && if (port.provides) it.provider == node.id && it.providerPort == port.key else it.consumer == node.id && it.consumerPort == port.key })
        } }
    }

    @Test fun realScipResolvesCrossFileDefinitionsReferencesAndImpact() = fixture { _, code ->
        val index = code.index("index.json")
        assertEquals("scip-typescript/0.4.0", index.provider)
        val definitions = index.query("use.ts", CodePosition(1, 24), "definition")
        assertEquals(3, definitions.total)
        assertTrue(definitions.occurrences.all { it.path == "api.ts" && it.definition && it.text == "compute" })
        val references = index.query("api.ts", CodePosition(0, 18), "references")
        assertEquals(3, references.total)
        assertTrue(references.occurrences.all { it.path == "use.ts" && !it.definition })
        val impact = index.query("api.ts", CodePosition(0, 18), "impact")
        assertEquals(setOf("api.ts", "use.ts"), impact.occurrences.map { it.path }.toSet())
        assertTrue(impact.omissions.single().contains("other targets"))
        assertEquals(1, index.query("api.ts", CodePosition(0, 18), "references", 1).occurrences.size)
        assertEquals(2, index.query("api.ts", CodePosition(0, 18), "references", 1).omissions.size)
        assertFailsWith<IllegalArgumentException> { index.query("use.ts", CodePosition(1, 29), "definition") }
    }

    @Test fun localRenameUsesResolvedUtf16RangesAndPreservesShadowedNamesStringsCommentsAndCrLf() = fixture { _, code ->
        val before = code.read("api.ts")
        val plan = code.renameLocal("index.json", "api.ts", CodePosition(3, 28), "computed")
        assertEquals("scip-local-rename-v1", plan.recipe)
        assertEquals(2, plan.replacements)
        assertTrue(plan.after.contains("const computed = value"))
        assertTrue(plan.after.contains("return computed;"))
        assertTrue(plan.after.contains("const result = 7; return result;"))
        assertTrue(plan.after.contains("\"result\""))
        assertTrue(plan.after.contains("// result must stay a comment"))
        assertEquals(before.text.count { it == '\r' }, plan.after.count { it == '\r' })
        assertEquals(before, code.read("api.ts"))
        assertFailsWith<IllegalArgumentException> { code.apply(plan, "different") }
        assertEquals(plan.after, code.apply(plan, plan.fingerprint).text)
        assertEquals(plan.after, code.apply(plan, plan.fingerprint).text)
        assertFailsWith<IllegalArgumentException> { code.index("index.json") }
    }

    @Test fun renameRejectsGlobalSymbolsCollisionsKeywordsAndShorthandCoupling() = fixture { _, code ->
        assertFailsWith<IllegalArgumentException> { code.renameLocal("index.json", "api.ts", CodePosition(0, 18), "renamed") }
        assertFailsWith<IllegalArgumentException> { code.renameLocal("index.json", "api.ts", CodePosition(3, 28), "note") }
        assertFailsWith<IllegalArgumentException> { code.renameLocal("index.json", "api.ts", CodePosition(3, 28), "return") }
        assertFailsWith<IllegalArgumentException> { code.renameLocal("index.json", "api.ts", CodePosition(10, 36), "renamed") }
    }

    @Test fun changedRelatedSourceOrTargetConfigurationInvalidatesTheWholeProposal() = fixture { root, code ->
        val plan = code.renameLocal("index.json", "api.ts", CodePosition(3, 28), "computed")
        Files.writeString(root.resolve("use.ts"), "export const changed = 1;\n")
        assertFailsWith<IllegalArgumentException> { code.apply(plan, plan.fingerprint) }
        assertFailsWith<IllegalArgumentException> { code.index("index.json") }
        assertEquals(plan.before, code.read("api.ts"))
    }

    @Test fun changedConfigurationAndForeignWorkspaceCannotQualifyAnIndex() = fixture { root, code ->
        val saved = Files.readString(root.resolve("tsconfig.json"))
        Files.writeString(root.resolve("tsconfig.json"), "{}")
        assertFailsWith<IllegalArgumentException> { code.index("index.json") }
        Files.writeString(root.resolve("tsconfig.json"), saved)
        val artifact = Json.parseToJsonElement(Files.readString(root.resolve("index.json"))).jsonObject
        val index = artifact.getValue("index").jsonObject
        val metadata = JsonObject(index.getValue("metadata").jsonObject + ("project_root" to JsonPrimitive("file:///tmp")))
        Files.writeString(root.resolve("index.json"), JsonObject(artifact + ("index" to JsonObject(index + ("metadata" to metadata)))).toString())
        assertFailsWith<IllegalArgumentException> { code.index("index.json") }
    }

    @Test fun localRenameIsQualifiedOnTypeScript6WithTheSameOccurrences() = fixture("index-typescript-6.json") { _, code ->
        val index = code.index("index.json")
        assertEquals("typescript/6.0.3", index.compiler)
        assertEquals(3, index.query("api.ts", CodePosition(0, 18), "references").total)
        val plan = code.renameLocal("index.json", "api.ts", CodePosition(3, 28), "computed")
        assertEquals(2, plan.replacements)
        assertTrue(plan.after.contains("const computed = value") && plan.after.contains("return computed;") && plan.after.contains("const result = 7; return result;"))
        assertEquals(plan.after, code.apply(plan, plan.fingerprint).text)
    }

    private fun fixture(artifact: String = "index.json", block: (Path, CodeWorkspace) -> Unit) {
        val root = Files.createTempDirectory("code-scip-").toRealPath()
        try {
            listOf("api.ts", "use.ts", "tsconfig.json", "package.json").forEach { name ->
                javaClass.getResourceAsStream("/scip/$name")!!.use { Files.copy(it, root.resolve(name)) }
            }
            javaClass.getResourceAsStream("/scip/$artifact")!!.use { Files.copy(it, root.resolve("index.json")) }
            val artifact = Json.parseToJsonElement(Files.readString(root.resolve("index.json"))).jsonObject
            val index = artifact.getValue("index").jsonObject
            val metadata = JsonObject(index.getValue("metadata").jsonObject + ("project_root" to JsonPrimitive(root.toUri().toString().removeSuffix("/"))))
            Files.writeString(root.resolve("index.json"), JsonObject(artifact + ("index" to JsonObject(index + ("metadata" to metadata)))).toString())
            block(root, CodeWorkspace(root))
        } finally { Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
