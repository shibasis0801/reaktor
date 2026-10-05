package dev.shibasis.reaktor.tooling.auth

import dev.shibasis.reaktor.tooling.database.BoundQuery
import dev.shibasis.reaktor.tooling.database.QueryParameterType
import dev.shibasis.reaktor.tooling.database.SqlReadStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuthConsoleSearchTest {
    private val reads: List<(String) -> BoundQuery> = listOf(
        { AuthConsoleQueries.sessions(search = it) },
        { AuthConsoleQueries.events(AuditFilter(search = it)) },
        { AuthTenancyQueries.people(search = it) },
    )

    @Test fun searchTextReachesTheDatabaseOnlyAsBoundParameters() {
        val hostile = """x'OR'1'='1 -device:\' email:%_\ ~a'|b\m user:$$ is:active age:200d"""
        val plain = "safe -device:safe email:safe ~safe user:safe is:active age:1d"
        reads.forEach { read ->
            val query = read(hostile)
            assertEquals(read(plain).statement, query.statement)
            listOf("x'OR", "%_", "a'|b", "$$").forEach { assertFalse(it in query.statement, it) }
            assertEquals(query.statement.count { it == '?' }, query.parameters.size)
            BoundQuery(SqlReadStatement.normalize(query.statement), query.parameters).validate()
        }
        val events = AuthConsoleQueries.events(AuditFilter(search = hostile)).parameters
        assertEquals(listOf("%x'OR'1'='1%", """%\\'%""", """%email:\%\_\\%""", """a'|b\m""", "%$$%", "%is:active%", "17280000"), events.map { it.value })
        assertEquals(QueryParameterType.Decimal, events.last().type)
    }

    @Test fun eachTermBindsOneValueHoweverManyColumnsItSearches() {
        val longest = List(60) { "a" }.joinToString(" ")
        reads.forEach { read ->
            val query = read(longest)
            assertEquals(60, query.parameters.size)
            BoundQuery(SqlReadStatement.normalize(query.statement), query.parameters).validate()
        }
    }
}
