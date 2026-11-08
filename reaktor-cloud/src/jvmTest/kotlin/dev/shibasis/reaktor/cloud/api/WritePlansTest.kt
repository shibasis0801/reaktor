package dev.shibasis.reaktor.cloud.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class WritePlansTest {
    private fun request(body: String) = PlannedRequest("PUT", "https://api.example/v1/thing", Json.parseToJsonElement(body))

    @Test fun theDigestIgnoresKeyOrderButNotValues() {
        assertEquals(WritePlans.digest("t", request("""{"a":1,"b":{"c":2,"d":3}}""")), WritePlans.digest("t", request("""{"b":{"d":3,"c":2},"a":1}""")))
        assertNotEquals(WritePlans.digest("t", request("""{"a":1}""")), WritePlans.digest("t", request("""{"a":2}""")))
        assertNotEquals(WritePlans.digest("t", request("""{"a":1}""")), WritePlans.digest("u", request("""{"a":1}""")))
    }

    @Test fun theDiffNamesEachChangedFieldAndSkipsBookkeeping() {
        val live = Json.parseToJsonElement("""{"version":3,"spec":{"replicas":1,"image":"a"},"tags":["x","y"]}""")
        val proposed = Json.parseToJsonElement("""{"version":9,"spec":{"replicas":2,"image":"a"},"tags":["x","z"],"owner":"ops"}""")
        assertEquals(listOf(
            FieldChange("owner", null, JsonPrimitive("ops")),
            FieldChange("spec.replicas", JsonPrimitive(1), JsonPrimitive(2)),
            FieldChange("tags[1]", JsonPrimitive("y"), JsonPrimitive("z")),
        ), JsonDiff.changes(live, proposed, setOf("version")))
    }

    @Test fun confirmsOnlyTheApprovedRequest() {
        val plan = WritePlans.plan("t", request("""{"a":1}"""), null, null)
        WritePlans.confirm(plan, plan.digest)
        assertFailsWith<IllegalStateException> { WritePlans.confirm(plan.copy(request = request("""{"a":2}""")), plan.digest) }
    }
}
