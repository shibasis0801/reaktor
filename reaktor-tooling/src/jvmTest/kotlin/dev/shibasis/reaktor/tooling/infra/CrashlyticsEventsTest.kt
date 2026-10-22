package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.json.*
import kotlin.test.*

class CrashlyticsEventsTest {
    private val app = "projects/123/apps/1:123:android:abc"
    private val trace = "a".repeat(32)
    private fun event(name: String = "$app/events/one") = buildJsonObject {
        put("name", name); put("eventTime", "2026-10-20T00:00:00Z")
        putJsonObject("customKeys") {
            put("reaktor.trace_id", trace); put("email", "private@example.com")
            put("reaktor.installation", "install-one"); put("reaktor.graph_digest", "invalid")
        }
        putJsonArray("logs") {
            addJsonObject { put("message", "private user message") }
            addJsonObject { put("message", "reaktor.application=bestbuds reaktor.trace_id=$trace reaktor.span_id=${"b".repeat(16)} reaktor.node=node-one") }
            addJsonObject { put("message", "reaktor.application=bestbuds reaktor.trace_id=$trace reaktor.trace_id=${"c".repeat(32)} reaktor.span_id=${"b".repeat(16)}") }
        }
    }
    @Test fun onlyValidatedReaktorContextAndStructuredBreadcrumbsLeaveTheReader() {
        val result = crashlyticsEvents(buildJsonObject { putJsonArray("events") { add(event()) } }, app)
        val retained = result.events.single()
        assertEquals(mapOf("reaktor.trace_id" to trace, "reaktor.installation" to "install-one"), retained.context)
        assertEquals(1, retained.breadcrumbs.size)
        assertEquals("node-one", retained.breadcrumbs.single().context["reaktor.node"])
        assertFalse(result.toString().contains("private"))
        assertFalse(result.bounded)
    }
    @Test fun wrongAppDuplicateEventsAndTruncatedApiResponsesAreRejected() {
        assertFails { crashlyticsEvents(buildJsonObject { putJsonArray("events") { add(event("projects/other/events/one")) } }, app) }
        assertFails { crashlyticsEvents(buildJsonObject { putJsonArray("events") { add(event()); add(event()) } }, app) }
        assertFails { crashlyticsApiBody(buildJsonObject { putJsonArray("content") { addJsonObject {
            put("text", """{"ok":true,"status":200,"truncated":true,"body":{}}""")
        } } }) }
    }
    @Test fun restMetricBucketsDoNotSumDistinctUsers() {
        fun report(metrics: String) = crashlyticsReport(buildJsonObject { putJsonArray("content") { addJsonObject {
            put("type", "text"); put("text", """{"name":"report","groups":[{"issue":{"id":"one"},"metrics":$metrics}]}""")
        } } })
        assertEquals(3L, report("""[{"eventsCount":"6","impactedUsersCount":"3"}]""").issues.single().users)
        assertNull(report("""[{"impactedUsersCount":"3"},{"impactedUsersCount":"3"}]""").issues.single().users)
    }
}
