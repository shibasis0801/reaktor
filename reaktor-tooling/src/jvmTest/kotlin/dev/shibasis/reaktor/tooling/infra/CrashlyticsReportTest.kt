package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.json.*
import kotlin.test.*

class CrashlyticsReportTest {
    private fun result(text: String) = buildJsonObject {
        putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
    }
    @Test fun nestedProviderYamlRetainsIssuesAndUnknownCounts() {
        val parsed = crashlyticsReport(result("""
            name: projects/123/apps/app/reports/topIssues
            groups: |
              - issue:
                  id: issue-one
                  title: Crash
                  subtitle: Main.kt
                  errorType: FATAL
                metrics:
                  eventsCount: '12'
                  impactedUsers: 3
              - issue:
                  id: issue-two
                  title: Other
        """.trimIndent()))
        assertEquals(12L, parsed.issues.first().events)
        assertEquals(3L, parsed.issues.first().users)
        assertNull(parsed.issues.last().events)
        assertFalse(parsed.bounded)
    }
    @Test fun errorsMalformedIdentityAndPageOverflowAreNotEmptyReports() {
        assertFails { crashlyticsReport(buildJsonObject { put("isError", true) }) }
        assertFails { crashlyticsReport(result("groups: []")) }
        assertFails { crashlyticsReport(result("name: report\ngroups: [{issue: {id: one}}, {issue: {id: two}}]"), limit = 1) }
        assertFails { crashlyticsReport(result("name: report\ngroups: [invalid]")) }
        assertTrue(crashlyticsReport(result("name: report\ngroups: []")).issues.isEmpty())
    }
}
