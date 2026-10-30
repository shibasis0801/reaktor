package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class DurableObjectMutationTest {
    @Test fun storageCommandsPreserveInstanceIdentityAndJsonValues() {
        val request = DurableObjectMutation("room:launch", false, DurableObjectChange.Storage("preferences", Json.parseToJsonElement("""{"muted":false,"count":3}""")))
        request.validate()
        val encoded = Json.encodeToString(request)
        assertTrue(encoded.contains("\"type\":\"storage\""))
        assertEquals(request, Json.decodeFromString<DurableObjectMutation>(encoded))
        request.copy(instance = "a".repeat(64), instanceId = true).validate()
        assertFailsWith<IllegalArgumentException> { request.copy(instanceId = true).validate() }
        assertFailsWith<IllegalArgumentException> { request.copy(instance = " ").validate() }
    }

    @Test fun commandsRejectOversizedOrInvalidStorageEditsBeforeDispatch() {
        fun command(key: String, value: String) = DurableObjectMutation("instance", false, DurableObjectChange.Storage(key, JsonPrimitive(value)))
        assertFailsWith<IllegalArgumentException> { command("", "value").validate() }
        assertFailsWith<IllegalArgumentException> { command("key\u0000", "value").validate() }
        assertFailsWith<IllegalArgumentException> { command("é".repeat(1025), "value").validate() }
        assertFailsWith<IllegalArgumentException> { command("key", "a".repeat(131_073)).validate() }
    }

    @Test fun keyReadsBindOneInstanceAndCannotBeCombinedWithSql() {
        val request = StoreKeyQuery(instance = "room:launch", key = "settings")
        request.validate("DurableObjects")
        assertEquals(request, Json.decodeFromString<StoreKeyQuery>(Json.encodeToString(request)))
        assertFailsWith<IllegalArgumentException> { request.copy(instance = null).validate("DurableObjects") }
        assertFailsWith<IllegalArgumentException> { request.copy(prefix = "other").validate("DurableObjects") }
        assertFailsWith<IllegalArgumentException> { request.copy(action = DurableObjectReadAction.Query, sql = "SELECT 1").validate("DurableObjects") }
        assertFailsWith<IllegalArgumentException> { request.copy(key = "").validate("DurableObjects") }
    }

    @Test fun alarmsAndDisconnectsUseDistinctCommands() {
        val clear = DurableObjectMutation("instance", false, DurableObjectChange.Alarm(null))
        val schedule = clear.copy(change = DurableObjectChange.Alarm(1_800_000_000_000))
        clear.validate()
        schedule.validate()
        assertEquals(clear, Json.decodeFromString<DurableObjectMutation>(Json.encodeToString(clear)))
        assertFailsWith<IllegalArgumentException> { clear.copy(change = DurableObjectChange.Alarm(-1)).validate() }
        assertFailsWith<IllegalArgumentException> { clear.copy(change = DurableObjectChange.Alarm(Long.MAX_VALUE)).validate() }
        val disconnect = clear.copy(change = DurableObjectChange.Disconnect)
        disconnect.validate()
        assertEquals(disconnect, Json.decodeFromString<DurableObjectMutation>(Json.encodeToString(disconnect)))
    }
}
