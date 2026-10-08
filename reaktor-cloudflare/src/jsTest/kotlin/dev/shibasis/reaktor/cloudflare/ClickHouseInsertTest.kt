package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class ClickHouseInsertTest {
    private class Fixture {
        val bodies = mutableListOf<String>()
        var responseBody = ""
        private val raw: dynamic = js("({})")
        init {
            raw.fetch = { _: dynamic, init: dynamic ->
                bodies += init.body.toString()
                Promise.resolve<dynamic>(response(responseBody, 200))
            }
        }
        val client = ClickHouse(WorkerService(raw.unsafeCast<RawServiceBinding>()), null, null, null)
    }

    @Test fun immutableRetryIdentityUsesSynchronousDeduplicatedInsert() = GlobalScope.promise {
        val fixture = Fixture()
        val rows = listOf("{\"id\":\"fixture\"}")
        fixture.client.insertJsonEachRow("fixture.events", rows, "client_abc123")
        fixture.client.insertJsonEachRow("fixture.events", rows, "client_abc123")
        assertEquals(fixture.bodies.first(), fixture.bodies.last())
        assertTrue(fixture.bodies.first().contains("async_insert=0, insert_deduplicate=1, insert_deduplication_token='client_abc123'"))
        assertTrue(fixture.bodies.first().endsWith(rows.single()))
    }

    @Test fun unsafeIdentityIsRejectedBeforeTheServiceBindingAndLegacyInsertIsUnchanged() = GlobalScope.promise {
        val fixture = Fixture()
        for (token in listOf("", "x' DROP TABLE fixture.events", "x".repeat(257))) {
            assertFailsWith<IllegalArgumentException> { fixture.client.insertJsonEachRow("fixture.events", listOf("{}"), token) }
        }
        assertTrue(fixture.bodies.isEmpty())
        fixture.client.insertJsonEachRow("fixture.events", listOf("{}"))
        assertEquals("INSERT INTO fixture.events FORMAT JSONEachRow\n{}", fixture.bodies.single())
    }

    @Test fun jsonQueryRecognizesExistingFormatInTheJavaScriptRuntime() = GlobalScope.promise {
        val fixture = Fixture()
        fixture.responseBody = "{\"data\":[]}"
        fixture.client.queryJson("SELECT 1 format json; ")
        fixture.client.queryJson("SELECT 1")
        assertEquals(listOf("SELECT 1 format json", "SELECT 1 FORMAT JSON"), fixture.bodies)
    }
}

private fun response(body: String, status: Int): dynamic = js("new Response(body, {status: status})")
