package dev.shibasis.reaktor.conductor.memory

import dev.shibasis.reaktor.conductor.ConductorJson
import java.net.URI

class MemoryResources(private val api: MemoryApi, private val scopes: List<MemoryScope>) {
    suspend fun list(): List<String> = api.search(MemoryQuery(scopes, limit = 100)).map { "memory://fact/${it.id}" }

    suspend fun read(uri: String): String {
        val parsed = URI(uri)
        require(parsed.scheme == "memory" && parsed.host == "fact" && parsed.query == null && parsed.fragment == null)
        val id = parsed.path.removePrefix("/")
        require(id.matches(Regex("[a-f0-9]{64}")))
        val fact = requireNotNull(api.get(id)) { "Unknown memory fact" }
        require(fact.scope in scopes) { "Memory is outside this session's scopes" }
        return ConductorJson.encodeToString(MemoryFact.serializer(), fact)
    }

    suspend fun sessionStart(links: List<String>): List<MemoryFact> = api.recall(RecallFacts(scopes, links))
}
