package dev.shibasis.reaktor.conductor.context

import java.lang.reflect.Proxy
import javax.sql.DataSource
import kotlin.test.*

class PgVectorCandidateSearchTest {
    private val noDatabase = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ ->
        error("No database connection should be opened")
    } as DataSource
    private val search = PgVectorCandidateSearch(noDatabase)
    private val request = CandidateSearchRequest("tenant", "workspace", "principal", "model-v1", listOf(1f, 0f),
        listOf(AuthorizedSourceRevision("source", "sha1")))

    @Test
    fun emptyAuthorizationNeverTouchesTheDatabase() {
        assertEquals(emptyList(), search.search(request.copy(authorizedSources = emptyList())))
    }

    @Test
    fun mismatchedOrInvalidSearchInputsFailBeforeOpeningAConnection() {
        for (invalid in listOf(
            request.copy(tenantId = ""), request.copy(principalId = ""), request.copy(embedding = listOf(Float.NaN)),
            request.copy(embedding = listOf(0f)), request.copy(limit = 101),
            request.copy(authorizedSources = listOf(AuthorizedSourceRevision("source", ""))),
        )) assertFailsWith<IllegalArgumentException> { search.search(invalid) }
    }

    @Test
    fun sourceIdentifiersAreBoundParametersAndEverySearchHasScopeModelAndRevisionFilters() {
        val injection = "source'); DROP TABLE reaktor_context_chunks; --"
        val query = search.buildQuery(request.copy(authorizedSources = listOf(AuthorizedSourceRevision(injection, "current-sha"))))
        assertFalse(query.sql.contains(injection))
        assertEquals(listOf(injection, "current-sha", "tenant", "workspace", "model-v1", 2, "[1.0,0.0]", 12), query.parameters)
        assertTrue(query.sql.contains("a.source_revision = c.source_revision"))
        assertTrue(query.sql.contains("c.tenant_id = ? AND c.workspace_id = ? AND c.embedding_model = ? AND c.dimensions = ?"))
        assertTrue(query.sql.contains("scoped AS MATERIALIZED"))
    }
}
