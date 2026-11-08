package dev.shibasis.reaktor.cloud.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteVersionTest {
    private fun version(body: String) = CloudApis.remoteVersion(Json.parseToJsonElement(body))

    @Test fun namesTheVersionEachPlatformReturns() {
        assertEquals("10523928", version("""{"kind":"ConfigMap","metadata":{"name":"x","resourceVersion":"10523928"}}"""))
        assertEquals("2026-11-06T10:00:00Z", version("""{"success":true,"result":{"id":"abc","modified_on":"2026-11-06T10:00:00Z"}}"""))
        assertEquals("4", version("""{"uid":"ops","status":"success","version":4}"""))
    }

    @Test fun namesTheCreatedResourceWhenItHasNoVersion() {
        assertEquals("abc", version("""{"success":true,"result":{"id":"abc","title":"reaktor-dev-check"}}"""))
        assertEquals("projects/p/topics/t", version("""{"name":"projects/p/topics/t","labels":{}}"""))
        assertNull(version("""{"message":"done"}"""))
    }
}
