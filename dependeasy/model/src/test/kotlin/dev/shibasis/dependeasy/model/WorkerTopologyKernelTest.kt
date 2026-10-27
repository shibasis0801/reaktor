package dev.shibasis.dependeasy.model

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkerTopologyKernelTest {
    @Test fun topologyIncludesPrivateBindingsAndEveryTriggerWithoutExportingConfigurationValues() {
        val settings = Json.parseToJsonElement("""{
            "vars":{"PASSWORD":"never-export","TOKEN":"also-private"},
            "services":[{"binding":"CHAT","service":"chat-private"}],
            "hyperdrive":[{"binding":"DB","id":"private-origin-id"}],
            "durable_objects":{"bindings":[{"name":"ROOMS","class_name":"Room"}]},
            "queues":{"producers":[{"binding":"FINISH","queue":"finish"}],"consumers":[{"queue":"finish"}]},
            "triggers":{"crons":["*/5 * * * *","15 0 * * *"]},
            "routes":[]
        }""").jsonObject
        val fleet = WorkerFleetContract(workers = listOf(WorkerDeployable("privateWorker", NodeId("fixture", ":workers", "privateWorker"),
            "targets/private", emptyMap(), emptyMap(), listOf(WorkerDeployment(Environment("prod"), ServiceRef("private-worker"))), mapOf("prod" to settings))))
        val projected = WorkerTopologyKernel.project(fleet)
        val deployment = projected.getValue("deployments").jsonArray.single().jsonObject
        assertEquals("privateWorker", deployment.getValue("worker").jsonPrimitive.content)
        assertEquals(4, deployment.getValue("bindings").jsonArray.size)
        assertEquals(2, deployment.getValue("crons").jsonArray.size)
        assertEquals("finish", deployment.getValue("queues").jsonArray.single().jsonPrimitive.content)
        assertTrue(projected.toString().contains("chat-private"))
        assertFalse(projected.toString().contains("never-export"))
        assertFalse(projected.toString().contains("also-private"))
        assertFalse(projected.toString().contains("private-origin-id"))
        assertEquals(projected, WorkerTopologyKernel.project(fleet))
    }
}
