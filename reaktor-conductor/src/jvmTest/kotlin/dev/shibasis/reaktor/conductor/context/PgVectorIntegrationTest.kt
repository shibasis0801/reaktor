package dev.shibasis.reaktor.conductor.context

import java.lang.reflect.Proxy
import java.sql.DriverManager
import java.io.File
import javax.sql.DataSource
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Opt-in against a disposable local PostgreSQL database; never uses the operator's app DB. */
class PgVectorIntegrationTest {
    @Test
    fun exactSearchExcludesOtherTenantsWorkspacesRevisionsModelsDimensionsAndUnauthorizedSources() {
        val url = System.getenv("REAKTOR_CONTEXT_TEST_JDBC_URL")
        assumeTrue("Set REAKTOR_CONTEXT_TEST_JDBC_URL to a disposable local pgvector database", url != null)
        require(url!!.startsWith("jdbc:postgresql://127.0.0.1:") && url.endsWith("/reaktor_context_test"))
        fun connection() = DriverManager.getConnection(url, "postgres", "conductor-test-only")
        connection().use { db ->
            db.createStatement().use { statement ->
                statement.execute(File("sql/context-pgvector.sql").readText())
                statement.execute("TRUNCATE reaktor_context_chunks")
                statement.execute("""
                    INSERT INTO reaktor_context_chunks
                    (tenant_id, workspace_id, source_id, source_revision, chunk_id, embedding_model, dimensions, embedding) VALUES
                    ('t','w','visible','current','ok','model',2,'[0.8,0.2]'),
                    ('other','w','visible','current','wrong-tenant','model',2,'[1,0]'),
                    ('t','other','visible','current','wrong-workspace','model',2,'[1,0]'),
                    ('t','w','hidden','current','hidden','model',2,'[1,0]'),
                    ('t','w','visible','old','stale','model',2,'[1,0]'),
                    ('t','w','visible','current','wrong-model','other',2,'[1,0]'),
                    ('t','w','visible','current','wrong-dimension','model',3,'[1,0,0]')
                """.trimIndent())
            }
        }
        val dataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            if (method.name == "getConnection") connection() else error("Unexpected DataSource operation: ${method.name}")
        } as DataSource
        val results = PgVectorCandidateSearch(dataSource).search(CandidateSearchRequest(
            "t", "w", "principal", "model", listOf(1f, 0f), listOf(AuthorizedSourceRevision("visible", "current")),
        ))
        assertEquals(listOf("ok"), results.map { it.chunkId })
        assertTrue(results.single().distance in 0.02..0.04)
    }
}
