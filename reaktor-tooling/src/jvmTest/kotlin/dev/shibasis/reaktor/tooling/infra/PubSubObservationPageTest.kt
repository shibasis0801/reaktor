package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.json.*
import kotlin.test.*

class PubSubObservationPageTest {
    private fun delivery(id: String, timestamp: Long) = buildJsonObject {
        put("messageId", id); put("receivedAtEpochMillis", timestamp); put("data", "private payload $id")
    }

    @Test
    fun tiedTimestampsAndIdenticalRedeliveriesSurvivePaginationAndNewerArrivals() {
        val duplicate = delivery("duplicate", 100)
        val original = listOf(delivery("older", 99), delivery("one", 100), duplicate, duplicate, delivery("two", 100))
        val (first, cursor) = pubSubObservationPage(original, null, 2)
        assertNotNull(cursor)
        assertFalse(cursor.contains("private"))
        val rows = first.toMutableList()
        var next: String? = cursor
        do {
            val page = pubSubObservationPage(listOf(delivery("newer", 101)) + original.reversed(), next, 2)
            rows.addAll(page.first)
            next = page.second
        } while (next != null)
        assertEquals(original.groupingBy { it }.eachCount(), rows.groupingBy { it }.eachCount())
        assertEquals("older", rows.last()["messageId"]?.jsonPrimitive?.content)
    }

    @Test
    fun invalidCursorsAndQueriesFailBeforeProviderAccess() {
        assertFailsWith<IllegalArgumentException> { pubSubObservationPage(emptyList(), "not-a-cursor", 10) }
        assertFailsWith<IllegalArgumentException> { pubSubObservationPage(emptyList(), null, 0) }
        assertFailsWith<IllegalArgumentException> { PubSubQuery(action = PubSubReadAction.Recent).validate() }
        assertFailsWith<IllegalArgumentException> { PubSubQuery(subscription = "x\" OR true").validate() }
        assertFailsWith<IllegalArgumentException> { PubSubQuery(metric = "arbitrary_metric").validate() }
        assertEquals("subscription-1", PubSubQuery(action = PubSubReadAction.Recent, subscription = "subscription-1").validate().subscription)
    }
}
