package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.json.*

internal suspend fun AgentWorkspace.collectKernelCheck(taskId: String, runId: String): AgentTaskEvidence {
    taskEvidence(taskId)
    suspend fun read(name: String, offset: Long? = null): JsonElement = readKernel(name, buildJsonObject {
        put("runId", runId); offset?.let { put("offset", it); put("limit", 100000) }
    })
    val receipt = read("check_result").jsonObject
    val result = AgentWorkspaceJson.decodeFromJsonElement(CheckResult.serializer(), receipt.getValue("result"))
    val candidateId = requireNotNull(result.revision) { "The kernel check has no stable source candidate" }
    require(evidence.get(taskId).candidates.any { it.id == candidateId }) { "Capture this source candidate before importing its check" }
    val localLog = result.log?.let { log ->
        require(log.bytes <= 16_000_000)
        val output = StringBuilder()
        var offset = 0L
        do {
            val page = AgentWorkspaceJson.decodeFromJsonElement(AgentArtifactPage.serializer(), read("check_artifact", offset))
            require(page.ref == log && page.offset == offset)
            output.append(page.text)
            val next = page.nextOffset
            if (next == null) break
            require(next > offset && next <= log.bytes)
            offset = next
        } while (true)
        evidence.artifacts.put(output.toString(), log.kind).also { require(it.id == log.id && it.bytes == log.bytes) { "Kernel artifact digest mismatch" } }
    }
    return evidence.check(taskId, AgentCandidateCheck(receipt.getValue("taskId").jsonPrimitive.content, candidateId,
        result.copy(log = localLog), "kernel"))
}
