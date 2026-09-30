package dev.shibasis.reaktor.conductor.context

import javax.sql.DataSource

/** Issued by the host's authorization/source resolver, never copied from an agent's search input. */
data class AuthorizedSourceRevision(val sourceId: String, val revision: String)

data class CandidateSearchRequest(
    val tenantId: String,
    val workspaceId: String,
    val principalId: String,
    val model: String,
    val embedding: List<Float>,
    val authorizedSources: List<AuthorizedSourceRevision>,
    val limit: Int = 12,
)

data class SemanticCandidate(val sourceId: String, val revision: String, val chunkId: String, val distance: Double)

/**
 * Optional PostgreSQL/pgvector adapter. Returns references, not trusted context or source content.
 * The host supplies an authorized current-revision allowlist and rehydrates/rechecks every result.
 * Exact cosine search is deliberate: filter by tenant, workspace, model, dimension and revision
 * before scoring. No extension setup, credentials, ingestion, embeddings or network fallback here.
 */
class PgVectorCandidateSearch(private val dataSource: DataSource, private val timeoutSeconds: Int = 5) {
    init { require(timeoutSeconds in 1..30) }

    fun search(request: CandidateSearchRequest): List<SemanticCandidate> {
        validate(request)
        if (request.authorizedSources.isEmpty()) return emptyList()
        val query = buildQuery(request)
        return dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.prepareStatement(query.sql).use { statement ->
                statement.queryTimeout = timeoutSeconds
                statement.maxRows = request.limit
                query.parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val candidate = SemanticCandidate(rows.getString(1), rows.getString(2), rows.getString(3), rows.getDouble(4))
                            check(candidate.distance.isFinite() && !rows.wasNull()) { "Invalid vector distance" }
                            add(candidate)
                        }
                    }
                }
            }
        }
    }

    internal data class Query(val sql: String, val parameters: List<Any>)

    internal fun buildQuery(request: CandidateSearchRequest): Query {
        validate(request)
        require(request.authorizedSources.isNotEmpty())
        val sources = request.authorizedSources.distinct()
        val placeholders = sources.joinToString(", ") { "(?::text, ?::text)" }
        return Query(
            sql = """
                WITH authorized(source_id, source_revision) AS (VALUES $placeholders),
                scoped AS MATERIALIZED (
                    SELECT c.source_id, c.source_revision, c.chunk_id, c.embedding
                    FROM reaktor_context_chunks c
                    JOIN authorized a ON a.source_id = c.source_id AND a.source_revision = c.source_revision
                    WHERE c.tenant_id = ? AND c.workspace_id = ? AND c.embedding_model = ? AND c.dimensions = ?
                )
                SELECT source_id, source_revision, chunk_id, embedding <=> ?::vector AS distance
                FROM scoped
                ORDER BY distance, source_id, source_revision, chunk_id
                LIMIT ?
            """.trimIndent(),
            parameters = sources.flatMap { listOf(it.sourceId, it.revision) } + listOf(
                request.tenantId, request.workspaceId, request.model, request.embedding.size,
                request.embedding.joinToString(prefix = "[", postfix = "]", separator = ","), request.limit,
            ),
        )
    }

    private fun validate(request: CandidateSearchRequest) {
        require(listOf(request.tenantId, request.workspaceId, request.principalId, request.model).all { it.isNotBlank() && it.length <= 256 })
        require(request.limit in 1..100)
        require(request.embedding.size in 1..4096 && request.embedding.all { it.isFinite() } && request.embedding.any { it != 0f })
        require(request.authorizedSources.size <= 5000)
        require(request.authorizedSources.all { it.sourceId.isNotBlank() && it.revision.isNotBlank() && it.sourceId.length <= 1024 && it.revision.length <= 256 })
    }
}
