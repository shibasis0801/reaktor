package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

@JsExport
open class SqlRow internal constructor(
    private val raw: dynamic,
) {
    @JsExport.Ignore
    operator fun get(columnName: String): Any? = raw[columnName]

    fun string(columnName: String): String? {
        val value: Any? = raw[columnName]
        return value?.asSqlString()
    }

    fun requireString(columnName: String): String =
        string(columnName) ?: error("Missing column '$columnName' in SQL row")

    fun int(columnName: String): Int? {
        val value = raw[columnName] ?: return null
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toIntOrNull()
        }
    }

    fun requireInt(columnName: String): Int =
        int(columnName) ?: error("Missing column '$columnName' in SQL row")

    fun double(columnName: String): Double? {
        val value = raw[columnName] ?: return null
        return when (value) {
            is Number -> value.toDouble()
            else -> value.toString().toDoubleOrNull()
        }
    }

    fun requireDouble(columnName: String): Double =
        double(columnName) ?: error("Missing column '$columnName' in SQL row")

    fun boolean(columnName: String): Boolean? {
        val value = raw[columnName] ?: return null
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value.toString().toBooleanStrictOrNull()
        }
    }

    fun requireBoolean(columnName: String): Boolean =
        boolean(columnName) ?: error("Missing column '$columnName' in SQL row")

    fun jsonText(columnName: String): String? =
        raw[columnName]?.let(::valueToJsonElement)?.toJsonText()

    @JsExport.Ignore
    fun <T> decode(serializer: KSerializer<T>): T =
        json.decodeFromJsonElement(serializer, projectedJson(serializer.descriptor))

    internal fun columnNames(): Array<String> = dynamicKeys(raw)

    internal fun jsonObject(): JsonObject {
        val root = linkedMapOf<String, Any?>()

        columnNames().forEach { columnName ->
            val value = raw[columnName]
            putPath(root, columnName.split('.'), value)

            val normalizedPath = columnName.split('.').map(::normalizeColumnSegment)
            if (normalizedPath.joinToString(".") != columnName) {
                putPath(root, normalizedPath, value)
            }
        }

        return root.toJsonObject()
    }

    private fun projectedJson(descriptor: SerialDescriptor): JsonElement =
        projectDescriptor(
            descriptor = descriptor,
            path = emptyList(),
            columns = columnLookup(),
        ) ?: jsonObject()

    private fun columnLookup(): ColumnLookup =
        ColumnLookup.build(raw, columnNames())
}

class SqlRowDecoder internal constructor(
    private val row: SqlRow,
) {
    fun string(columnName: String): String = row.requireString(columnName)

    fun stringOrNull(columnName: String): String? = row.string(columnName)

    fun int(columnName: String): Int = row.requireInt(columnName)

    fun intOrNull(columnName: String): Int? = row.int(columnName)

    fun double(columnName: String): Double = row.requireDouble(columnName)

    fun doubleOrNull(columnName: String): Double? = row.double(columnName)

    fun boolean(columnName: String): Boolean = row.requireBoolean(columnName)

    fun booleanOrNull(columnName: String): Boolean? = row.boolean(columnName)

    fun value(columnName: String): Any? = row[columnName]

    fun jsonElement(columnName: String): JsonElement? =
        value(columnName)?.let(::valueToJsonElement)

    fun jsonObject(columnName: String): JsonObject =
        jsonElement(columnName) as? JsonObject
            ?: error("Column '$columnName' is not a JSON object")

    fun jsonObjectOrNull(columnName: String): JsonObject? =
        jsonElement(columnName) as? JsonObject
}

inline fun <reified T> SqlRow.decode(): T = decode(kSerializer<T>())

fun <T> SqlRow.decode(block: SqlRowDecoder.() -> T): T =
    SqlRowDecoder(this).block()

private fun normalizeColumnSegment(segment: String): String {
    if (!segment.contains('_')) {
        return segment
    }

    return segment
        .split('_')
        .filter(String::isNotEmpty)
        .mapIndexed { index, part ->
            if (index == 0) {
                part
            } else {
                part.replaceFirstChar(Char::uppercaseChar)
            }
        }
        .joinToString("")
}

private fun dynamicKeys(value: dynamic): Array<String> =
    js("Object.keys(arguments[0])") as Array<String>

private data class ColumnValue(
    val rawName: String,
    val value: Any?,
)

private class ColumnLookup private constructor(
    private val exact: Map<String, ColumnValue>,
    private val fallback: Map<String, List<ColumnValue>>,
) {
    operator fun get(path: List<String>): ColumnValue? {
        exact[canonicalKey(path.joinToString("."))]?.let { return it }
        exact[canonicalKey(path.joinToString("_"))]?.let { return it }

        val leaf = canonicalKey(path.lastOrNull().orEmpty())
        return fallback[leaf]?.singleOrNull()
    }

    companion object {
        fun build(
            raw: dynamic,
            columnNames: Array<String>,
        ): ColumnLookup {
            val exact = linkedMapOf<String, ColumnValue>()
            val fallback = linkedMapOf<String, MutableList<ColumnValue>>()

            columnNames.forEach { columnName ->
                val column = ColumnValue(columnName, raw[columnName])
                exact.putWhenMissing(canonicalKey(columnName), column)

                val normalizedPath = columnName.split('.').map(::normalizeColumnSegment)
                exact.putWhenMissing(canonicalKey(normalizedPath.joinToString(".")), column)
                exact.putWhenMissing(canonicalKey(normalizedPath.joinToString("_")), column)

                if (!columnName.contains('.')) {
                    columnFallbackKeys(columnName).forEach { alias ->
                        fallback.getOrPut(alias) { mutableListOf() }.add(column)
                    }
                }
            }

            return ColumnLookup(exact, fallback)
        }
    }
}

private fun projectDescriptor(
    descriptor: SerialDescriptor,
    path: List<String>,
    columns: ColumnLookup,
): JsonElement? {
    val kind = descriptor.kind
    if (kind == StructureKind.CLASS || kind == StructureKind.OBJECT) {
        val fields = linkedMapOf<String, JsonElement>()
        repeat(descriptor.elementsCount) { index ->
            val name = descriptor.getElementName(index)
            projectDescriptor(
                descriptor = descriptor.getElementDescriptor(index),
                path = path + name,
                columns = columns,
            )?.let { fields[name] = it }
        }
        return if (fields.isEmpty()) JsonObject(emptyMap()) else JsonObject(fields)
    }

    val resolved = resolveProjectedValue(path, columns)
    if (!resolved.found) {
        return null
    }

    val value = resolved.value
    return if (value == null) {
        if (path.size <= 1) JsonNull else null
    } else {
        valueToJsonElement(value)
    }
}

private fun resolveProjectedValue(
    path: List<String>,
    columns: ColumnLookup,
): NestedValue {
    columns[path]?.let { return NestedValue(found = true, value = it.value) }

    for (index in path.lastIndex downTo 1) {
        val parent = columns[path.take(index)] ?: continue
        val nested = nestedValue(parent.value, path.drop(index))
        if (nested.found) {
            return nested
        }
    }

    return NestedValue(found = false, value = null)
}

private fun canonicalKey(value: String): String =
    value.filter(Char::isLetterOrDigit).lowercase()

private fun columnFallbackKeys(columnName: String): List<String> {
    val words = splitColumnWords(columnName)
    if (words.size < 2) {
        return emptyList()
    }

    return buildList {
        for (index in 1 until words.size) {
            add(canonicalKey(words.drop(index).joinToString("")))
        }
    }
}

private fun splitColumnWords(columnName: String): List<String> =
    columnName
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotBlank)
        .map(String::lowercase)

private data class NestedValue(
    val found: Boolean,
    val value: Any?,
)

private fun nestedValue(
    root: Any?,
    path: List<String>,
): NestedValue {
    if (path.isEmpty()) {
        return NestedValue(found = true, value = root)
    }

    val entries = objectEntries(root) ?: return NestedValue(found = false, value = null)
    val next = entries.entries.firstOrNull { canonicalKey(it.key) == canonicalKey(path.first()) }
        ?: return NestedValue(found = false, value = null)

    return nestedValue(next.value, path.drop(1))
}

private fun objectEntries(value: Any?): Map<String, Any?>? =
    when (value) {
        null -> null
        is JsonObject -> value.mapValues { (_, nested) -> nested }
        is Map<*, *> -> value.entries.associate { (key, nested) -> key.toString() to nested }
        else -> if (isPlainJsObject(value)) {
            dynamicKeys(value).associateWith { key -> value.asDynamic()[key] }
        } else {
            null
        }
    }

private fun MutableMap<String, ColumnValue>.putWhenMissing(
    key: String,
    value: ColumnValue,
) {
    if (key !in this) {
        this[key] = value
    }
}

@Suppress("UNCHECKED_CAST")
private fun putPath(
    target: MutableMap<String, Any?>,
    path: List<String>,
    value: Any?,
) {
    if (path.isEmpty()) return
    if (value == null && path.size > 1) return

    val head = path.first()
    if (path.size == 1) {
        if (value != null || head !in target) {
            target[head] = value
        }
        return
    }

    val child = target.getOrPut(head) { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
    putPath(child, path.drop(1), value)
}

private fun Map<String, Any?>.toJsonObject(): JsonObject =
    JsonObject(mapValues { (_, value) -> valueToJsonElement(value) })

private fun valueToJsonElement(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Map<*, *> -> value.entries.associate { (key, nested) -> key.toString() to nested }.toJsonObject()
        is Iterable<*> -> JsonArray(value.map(::valueToJsonElement))
        is Array<*> -> JsonArray(value.map(::valueToJsonElement))
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        else -> when {
            isJsDate(value) -> JsonPrimitive(jsDateIsoString(value))
            isPlainJsObject(value) -> dynamicObjectToJson(value)
            else -> JsonPrimitive(value.toString())
        }
    }

private fun Any.asSqlString(): String =
    when {
        isJsDate(this) -> jsDateIsoString(this)
        else -> toString()
    }

private fun isJsDate(value: Any): Boolean =
    jsTypeOf(value) == "object" && js("value instanceof Date") as Boolean

private fun isJsArray(value: Any): Boolean =
    js("Array.isArray(value)") as Boolean

private fun isPlainJsObject(value: Any): Boolean =
    jsTypeOf(value) == "object" &&
        !isJsDate(value) &&
        !isJsArray(value)

private fun jsDateIsoString(value: Any): String =
    js("value.toISOString()") as String

private fun dynamicObjectToJson(value: Any): JsonObject =
    JsonObject(
        dynamicKeys(value)
            .associateWith { key ->
                val nestedValue: Any? = value.asDynamic()[key]
                valueToJsonElement(nestedValue)
            },
    )
