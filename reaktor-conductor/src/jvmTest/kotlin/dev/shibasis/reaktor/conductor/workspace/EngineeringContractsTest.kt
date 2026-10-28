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
        assertEquals(before.keys, calls.keys)
        for ((id, call) in calls) {
            val expected = before.getValue(id)
            assertEquals(expected.getValue("summary").jsonPrimitive.content, call.summary, "$id summary")
            assertEquals(expected.getValue("effect").jsonPrimitive.content, call.effect.name, "$id effect")
            assertEquals(expected.getValue("input"), call.request.descriptor.jsonSchema(), "$id input")
            assertEquals(expected["output"], call.response.descriptor.jsonSchema().takeIf { it.isNotEmpty() }, "$id output")
        }
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
    }
}
