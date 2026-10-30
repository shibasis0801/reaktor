@file:OptIn(ExperimentalJsExport::class)

package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.database.QueryColumn
import dev.shibasis.reaktor.tooling.database.QueryReceipt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.js.*

@JsExport
class DurableObjectStorageInspector(private val storage: Any) {
    fun read(payload: String): Promise<String> {
        require(payload.encodeToByteArray().size <= 262_144)
        val request = Json.decodeFromString<StoreKeyQuery>(payload)
        request.validate("DurableObjects")
        val key = requireNotNull(request.key)
        val pending: Promise<Any?> = storage.asDynamic().get(key).unsafeCast<Promise<Any?>>()
        return pending.then<String> { value: Any? ->
            val exists = jsTypeOf(value) != "undefined"
            Json.encodeToString(QueryReceipt(provider = "DurableObjects",
                columns = listOf(QueryColumn("key", "string"), QueryColumn("exists", "boolean"), QueryColumn("value", "json")),
                rows = listOf(listOf(JsonPrimitive(key), JsonPrimitive(exists), if (exists) jsonValue(value) else JsonNull))))
        }
    }

    fun change(payload: String, connections: Array<Any>, capabilities: Array<String>): Promise<String> {
        require(payload.encodeToByteArray().size <= 262_144)
        val request = Json.decodeFromString<DurableObjectMutation>(payload).also { it.validate() }
        val command = request.change
        val capability = when (command) {
            is DurableObjectChange.Storage -> "storage-change"
            is DurableObjectChange.Alarm -> "alarm-change"
            DurableObjectChange.Disconnect -> "disconnect"
        }
        require(capability in capabilities) { "This class does not support $capability" }
        val applied: Promise<Any?> = when (command) {
            is DurableObjectChange.Storage -> storage.asDynamic().put(command.key, JSON.parse<dynamic>(command.value.toString())).unsafeCast<Promise<dynamic>>()
            is DurableObjectChange.Alarm -> if (command.atMillis == null) storage.asDynamic().deleteAlarm().unsafeCast<Promise<dynamic>>()
                else storage.asDynamic().setAlarm(command.atMillis.toDouble()).unsafeCast<Promise<dynamic>>()
            DurableObjectChange.Disconnect -> {
                connections.forEach { it.asDynamic().close(1001, "Closed by the Hangar operator") }
                Promise.resolve<dynamic>(connections.size)
            }
        }
        return applied.then<String> { _: Any? ->
            buildJsonObject {
                put("applied", true)
                when (command) {
                    is DurableObjectChange.Storage -> { put("kind", "storage"); put("key", command.key); put("value", command.value) }
                    is DurableObjectChange.Alarm -> { put("kind", "alarm"); put("alarm_at", command.atMillis?.let(::JsonPrimitive) ?: JsonNull) }
                    DurableObjectChange.Disconnect -> { put("kind", "disconnect"); put("close_requested", connections.size) }
                }
            }.toString()
        }
    }
}

private fun jsonValue(value: dynamic): JsonElement {
    val encode = js("""(function(value) {
        const seen = new Set();
        function check(v) {
            if (v === null || typeof v === 'string' || typeof v === 'boolean') return;
            if (typeof v === 'number' && Number.isFinite(v)) return;
            if (typeof v !== 'object' || seen.has(v)) throw new Error('Storage preview supports finite JSON values only');
            if (!Array.isArray(v) && Object.getPrototypeOf(v) !== Object.prototype && Object.getPrototypeOf(v) !== null) throw new Error('Storage value is not JSON');
            seen.add(v);
            for (const child of Object.values(v)) check(child);
            seen.delete(v);
        }
        check(value);
        return JSON.stringify(value);
    })""")
    val encoded = encode(value) as String
    require(encoded.encodeToByteArray().size <= 131_072) { "Storage preview exceeds 128 KiB" }
    return Json.parseToJsonElement(encoded)
}
