package dev.shibasis.reaktor.conductor.workspace

import com.sun.management.ThreadMXBean
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.lang.management.ManagementFactory
import kotlin.test.*

class AgentRunTransferAllocationTest {
    @Test fun anUnboundedJournalPatchDoesNotSerializeTheAnswerForEveryBinarySearchStep() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean?.isThreadAllocatedMemorySupported == true)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val run = AgentRunRecord("allocation", "fixture", RuntimeKind.Echo, "fingerprint", "Journal allocation",
            startedAt = 1, updatedAt = 1)
        AgentRunTransfer.delta(run.copy(output = "Warm serializers"), JsonObject(emptyMap()), Int.MAX_VALUE, false)
        val answer = "x".repeat(5_000_000)
        val current = run.copy(output = answer)
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val delta = AgentRunTransfer.delta(current, JsonObject(emptyMap()), Int.MAX_VALUE, false)
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        println("JOURNAL_PATCH_ALLOCATED_BYTES=$allocated")
        assertEquals(answer, delta.getValue("streams").jsonObject.getValue("output").jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(delta.getValue("complete").jsonPrimitive.boolean)
        assertTrue(allocated < 96L * 1024 * 1024, "A full journal patch allocated $allocated bytes")
    }
}
