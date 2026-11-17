package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.AgentEvent
import java.io.File
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.*
import kotlin.test.*

class CodexLiveCostTest {
    @Test fun smallDeltasAppendWithoutRebuildingTheWholeAnswer() {
        val parser = CodexLog("/fixture")
        val agent = AgentId("fixture")
        val bean = ManagementFactory.getThreadMXBean()
        val cpu = bean.currentThreadCpuTime
        var delivered = 0
        repeat(50_000) {
            val row = buildJsonObject { put("type", "event_msg"); putJsonObject("payload") { put("type", "agent_message_delta"); put("delta", "x".repeat(20)) } }
            delivered += parser.events(row, agent).filterIsInstance<AgentEvent.Delta>().sumOf { it.text.length }
        }
        val cpuMillis = (bean.currentThreadCpuTime - cpu) / 1_000_000
        System.getenv("HANGAR_ORDER13_COSTS")?.let { folder -> File(folder).mkdirs(); File(folder, "codex-delta-cpu.json").writeText("""{"characters":1000000,"chunks":50000,"cpuMillis":$cpuMillis}""") }
        assertEquals(1_000_000, delivered)
        assertEquals(1_000_000, parser.log(false).turns.single().steps.filterIsInstance<WorkStep.Message>().single().text.length)
        assertTrue(cpuMillis < 1000, "Small deltas used $cpuMillis ms of CPU")
    }
}
