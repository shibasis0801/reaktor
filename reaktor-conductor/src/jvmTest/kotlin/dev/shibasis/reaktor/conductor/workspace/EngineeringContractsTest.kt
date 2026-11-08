package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.service.contract.Call
import dev.shibasis.reaktor.tooling.atlas.AtlasApi
import dev.shibasis.reaktor.tooling.changes.ChangesApi
import dev.shibasis.reaktor.tooling.checks.ChecksApi
import dev.shibasis.reaktor.tooling.operation.OperationJson
import dev.shibasis.reaktor.tooling.operation.jsonSchema
import dev.shibasis.reaktor.tooling.runs.RunsApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class EngineeringContractsTest {
    @Test
    fun engineeringContractsKeepTheCapturedPublicDescriptors() {
        val stream = assertNotNull(javaClass.getResourceAsStream("/engineering-contracts-before.json"))
        val before = stream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
            .associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        val calls = listOf(AtlasApi(), ChangesApi(), ChecksApi(), RunsApi(), PlansApi()).flatMap { api ->
            api.operations.filterIsInstance<Call<*, *>>().map { call -> "${api.id.substringAfterLast('.')}.${call.name}" to call }
        }.toMap()
        assertEquals(before.keys + setOf("plan.next", "plan.schedule"), calls.keys)
        for ((id, expected) in before) {
            val call = calls.getValue(id)
            assertEquals(expected.getValue("summary").jsonPrimitive.content, call.summary, "$id summary")
            assertEquals(expected.getValue("effect").jsonPrimitive.content, call.effect.name, "$id effect")
            val input = call.request.descriptor.jsonSchema()
            val comparable = if (id == "plan.write") {
                val properties = input.getValue("properties").jsonObject
                val ops = properties.getValue("ops").jsonObject
                val description = ops.getValue("description").jsonPrimitive.content
                for (field in listOf("priority", "reason", "replacement", "detail", "previews")) kotlin.test.assertTrue(field in description)
                assertEquals("string", properties.getValue("documentPath").jsonObject.getValue("type").jsonPrimitive.content)
                kotlin.test.assertTrue(input.getValue("required").jsonArray.none { it.jsonPrimitive.content == "documentPath" })
                JsonObject(input + ("properties" to JsonObject((properties - "documentPath") + ("ops" to JsonObject(ops +
                    ("description" to expected.getValue("input").jsonObject.getValue("properties").jsonObject.getValue("ops").jsonObject.getValue("description")))))))
            } else input
            assertEquals(expected.getValue("input"), comparable, "$id input")
            val output = call.response.descriptor.jsonSchema().takeIf { it.isNotEmpty() }
            val additions = when {
                id == "atlas.query" -> setOf("memory")
                id.startsWith("plan.") -> setOf("priority", "reason", "replacement", "lease", "agent", "profile", "profileRevision", "previews", "completion", "documentPath")
                else -> emptySet()
            }
            assertEquals(expected["output"], baselineOutput(expected["output"], output, additions), "$id output")
        }
    }

    private fun baselineOutput(expected: JsonElement?, actual: JsonElement?, additions: Set<String>): JsonElement? {
        if (expected is JsonArray && actual is JsonArray && expected.size == actual.size)
            return JsonArray(expected.zip(actual).map { (old, current) -> baselineOutput(old, current, additions)!! })
        if (expected !is JsonObject || actual !is JsonObject) return actual
        val oldProperties = expected["properties"] as? JsonObject
        val properties = actual["properties"] as? JsonObject
        val normalized = if (oldProperties != null && properties != null) {
            val added = properties.keys - oldProperties.keys
            kotlin.test.assertTrue(additions.containsAll(added), "Unexpected output properties: $added")
            val required = (actual["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            kotlin.test.assertTrue(added.none { it in required }, "Added output properties must be optional")
            JsonObject(actual + ("properties" to JsonObject(properties - added)))
        } else actual
        return JsonObject(normalized.mapValues { (key, value) -> baselineOutput(expected[key], value, additions)!! })
    }

    @Test
    fun checkRunsDefaultToAffectedClassesAndKeepBroadRunsExplicit() {
        val serializer = dev.shibasis.reaktor.tooling.checks.CheckRunRequest.serializer()
        val defaults = OperationJson.decodeFromJsonElement(serializer, JsonObject(emptyMap()))
        assertEquals(true, defaults.narrow)
        val broad = OperationJson.decodeFromJsonElement(serializer, buildJsonObject { put("narrow", false) })
        assertEquals(false, broad.narrow)
        assertEquals(null, defaults.planItem)
        assertEquals(null, defaults.queue)
    }

    @Test
    fun schedulingAddsReadDescriptorsWithoutCallerSuppliedIdentity() {
        val api = PlansApi()
        assertEquals("ReadOnly", api.next.effect.name)
        assertEquals("ReadOnly", api.schedule.effect.name)
        val nextInput = api.next.request.descriptor.jsonSchema()
        assertEquals(setOf("thread", "agent", "seat", "take", "item"), nextInput.getValue("properties").jsonObject.keys)
        assertEquals("string", nextInput.getValue("properties").jsonObject.getValue("item").jsonObject.getValue("type").jsonPrimitive.content)
        kotlin.test.assertTrue((nextInput["required"] as? JsonArray).orEmpty().none { it.jsonPrimitive.content == "item" })
        assertEquals(setOf("thread"), api.schedule.request.descriptor.jsonSchema().getValue("properties").jsonObject.keys)
    }

    @Test
    fun planWritersCannotSupplyAnActorOrUnknownArguments() {
        for (field in listOf("by", "approvedBy", "caller")) {
            assertFailsWith<SerializationException> {
                OperationJson.decodeFromJsonElement(PlanWriteRequest.serializer(), buildJsonObject {
                    put(field, buildJsonObject { put("kind", "Person"); put("name", "injected") })
                })
            }
        }
        val request = OperationJson.decodeFromJsonElement(PlanWriteRequest.serializer(), JsonObject(emptyMap()))
        assertEquals(emptyList(), request.ops)
        assertEquals(null, request.documentPath)
    }
}
