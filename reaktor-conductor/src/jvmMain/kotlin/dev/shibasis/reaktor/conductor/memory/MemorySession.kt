package dev.shibasis.reaktor.conductor.memory

import dev.shibasis.reaktor.conductor.ContextEntry
import dev.shibasis.reaktor.conductor.workspace.AgentRunRecord
import dev.shibasis.reaktor.tooling.cloud.AiGatewayClient
import java.io.File

class MemorySession(private val api: MemoryApi, private val root: File) {
    suspend fun start(thread: String, agent: String, links: List<String>, currentRevision: String): List<ContextEntry> {
        val scopes = listOf(MemoryScope(MemoryScopeKind.Workspace, root.canonicalPath),
            MemoryScope(MemoryScopeKind.Repository, root.canonicalPath), MemoryScope(MemoryScopeKind.Plan, thread), MemoryScope(MemoryScopeKind.Agent, agent))
        return MemoryResources(api, scopes).sessionStart(links).map { fact ->
            ContextEntry("memory:${fact.id}", "agent-memory", fact.title, fact.text.take(1500),
                "Reviewed assertion; revision=${fact.revision}; source=${when {
                    fact.provenance.any { it.sourceRevision == currentRevision } -> "matching"
                    fact.provenance.any { it.sourceRevision != null } -> "stale"
                    else -> "unknown"
                }}; provenance=${fact.provenance.joinToString { it.source }}")
        }
    }

    suspend fun finish(run: AgentRunRecord, agent: String, messages: List<String>): List<MemoryFact>? {
        val gateway = AiGatewayClient.forWorkspace(root) ?: return null
        return MemoryExtractor.gateway(api, gateway).extract(run, MemoryScope(MemoryScopeKind.Plan, "hangar:${run.threadId}"), agent, messages)
    }
}
