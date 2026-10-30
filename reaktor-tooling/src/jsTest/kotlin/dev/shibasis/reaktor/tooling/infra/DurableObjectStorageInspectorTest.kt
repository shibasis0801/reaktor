package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.database.QueryReceipt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.js.Promise
import kotlin.test.*

class DurableObjectStorageInspectorTest {
    @Test fun writesAwaitStorageAndReturnTheAppliedChange(): Promise<Unit> {
        val storage = fixture()
        val inspector = DurableObjectStorageInspector(storage)
        val value = Json.parseToJsonElement("""{"muted":true,"limit":3}""")
        val request = DurableObjectMutation("room:launch", false, DurableObjectChange.Storage("settings", value))
        return inspector.change(Json.encodeToString(request), emptyArray(), arrayOf("storage-change")).then { output ->
            val receipt = Json.parseToJsonElement(output).jsonObject
            assertTrue(receipt.getValue("applied").jsonPrimitive.boolean)
            assertEquals(value, receipt["value"])
            assertEquals(value.toString(), JSON.stringify(storage.asDynamic().values.get("settings")))
            Unit
        }
    }

    @Test fun alarmAndConnectionCommandsRespectClassCapabilities(): Promise<Unit> {
        val storage = fixture()
        val inspector = DurableObjectStorageInspector(storage)
        val schedule = DurableObjectMutation("room:launch", false, DurableObjectChange.Alarm(1_800_000_000_000))
        assertFailsWith<IllegalArgumentException> { inspector.change(Json.encodeToString(schedule), emptyArray(), arrayOf("storage-change")) }
        val socket = js("({ closed: 0, close(code, reason) { this.closed++; this.code = code; } })")
        return inspector.change(Json.encodeToString(schedule), emptyArray(), arrayOf("alarm-change")).then {
            assertEquals(1_800_000_000_000.0, storage.asDynamic().alarm as Double)
            inspector.change(Json.encodeToString(schedule.copy(change = DurableObjectChange.Alarm(null))), emptyArray(), arrayOf("alarm-change"))
        }.then {
            assertNull(storage.asDynamic().alarm)
            inspector.change(Json.encodeToString(schedule.copy(change = DurableObjectChange.Disconnect)), arrayOf(socket), arrayOf("disconnect"))
        }.then { output ->
            assertEquals(1, socket.closed as Int)
            assertEquals(1001, socket.code as Int)
            assertEquals(1, Json.parseToJsonElement(output).jsonObject.getValue("close_requested").jsonPrimitive.int)
            Unit
        }
    }

    @Test fun rejectedStoragePromisesNeverProduceAnAppliedReceipt(): Promise<Unit> {
        val storage = js("({ put() { return Promise.reject(new Error('write refused')); } })")
        val request = DurableObjectMutation("room:launch", false, DurableObjectChange.Storage("settings", JsonNull))
        return DurableObjectStorageInspector(storage).change(Json.encodeToString(request), emptyArray(), arrayOf("storage-change")).then {
            fail("A failed write returned an applied receipt")
        }.catch { error ->
            assertTrue(error.message.orEmpty().contains("write refused"))
            Unit
        }
    }

    @Test fun keyReadsDistinguishMissingValuesFromStoredJsonNull(): Promise<Unit> {
        val storage = fixture()
        storage.asDynamic().values.set("settings", null)
        val inspector = DurableObjectStorageInspector(storage)
        fun read(key: String) = inspector.read(Json.encodeToString(StoreKeyQuery(instance = "room:launch", key = key)))
        return read("missing").then { output ->
            val receipt = Json.decodeFromString<QueryReceipt>(output)
            assertEquals(listOf(JsonPrimitive("missing"), JsonPrimitive(false), JsonNull), receipt.rows.single())
            read("settings")
        }.then { output ->
            val receipt = Json.decodeFromString<QueryReceipt>(output)
            assertEquals(listOf(JsonPrimitive("settings"), JsonPrimitive(true), JsonNull), receipt.rows.single())
            Unit
        }
    }

    @Test fun nonJsonStorageValuesAreRejectedInsteadOfDisplayedAsEmptyObjects(): Promise<Unit> {
        val storage = fixture()
        storage.asDynamic().values.set("binary", js("new ArrayBuffer(8)"))
        val request = StoreKeyQuery(instance = "room:launch", key = "binary")
        return DurableObjectStorageInspector(storage).read(Json.encodeToString(request)).then {
            fail("A binary value was presented as editable JSON")
        }.catch { error ->
            assertTrue(error.message.orEmpty().contains("not JSON"))
            Unit
        }
    }

    private fun fixture(): Any = js("""({
        values: new Map(), alarm: null,
        get(key) { return Promise.resolve(this.values.get(key)); },
        put(key, value) { this.values.set(key, value); return Promise.resolve(); },
        setAlarm(at) { this.alarm = at; return Promise.resolve(); },
        deleteAlarm() { this.alarm = null; return Promise.resolve(); }
    })""")
}
