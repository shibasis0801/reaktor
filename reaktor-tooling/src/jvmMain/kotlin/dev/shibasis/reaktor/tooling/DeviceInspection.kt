package dev.shibasis.reaktor.tooling

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import kotlinx.serialization.json.*

data class DeviceViewElement(
    val id: String,
    val parentId: String?,
    val depth: Int,
    val attributes: Map<String, String>,
) {
    val label: String get() = attributes["class"] ?: attributes["type"] ?: attributes["role"] ?: "Element"
    val text: String get() = listOf("text", "content-desc", "AXLabel", "label", "AXValue")
        .firstNotNullOfOrNull { attributes[it]?.takeIf(String::isNotBlank) }.orEmpty()
    val resourceId: String get() = attributes["resource-id"] ?: attributes["AXUniqueId"] ?: attributes["identifier"].orEmpty()
    val bounds: String get() = attributes["bounds"] ?: attributes["frame"].orEmpty()
    val rectangle: DeviceViewBounds? get() = DeviceViewBounds.parse(bounds)
}

data class DeviceViewBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    companion object {
        fun parse(value: String): DeviceViewBounds? = runCatching {
            val android = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matchEntire(value)
            if (android != null) android.groupValues.drop(1).map(String::toFloat).let { DeviceViewBounds(it[0], it[1], it[2], it[3]) }
            else Json.parseToJsonElement(value).jsonObject.let { frame ->
                fun number(key: String) = frame.getValue(key).jsonPrimitive.float
                val x = number("x"); val y = number("y")
                DeviceViewBounds(x, y, x + number("width"), y + number("height"))
            }
        }.getOrNull()?.takeIf { listOf(it.left, it.top, it.right, it.bottom).all(Float::isFinite) && it.width > 0 && it.height > 0 }
    }
}

data class DeviceViewSnapshot(val elements: List<DeviceViewElement>) {
    fun visible(collapsed: Set<String>, search: String = ""): List<DeviceViewElement> {
        val byId = elements.associateBy { it.id }
        if (search.isNotBlank()) {
            val matches = elements.filter { it.attributes.values.any { value -> value.contains(search, true) } }
            val visible = mutableSetOf<String>()
            matches.forEach { match ->
                var cursor: DeviceViewElement? = match
                while (cursor != null && visible.add(cursor.id)) cursor = cursor.parentId?.let(byId::get)
            }
            return elements.filter { it.id in visible }
        }
        val hidden = mutableSetOf<String>()
        return elements.filter { element ->
            if (element.parentId in collapsed || element.parentId in hidden) { hidden.add(element.id); false } else true
        }
    }
}

/** Parsers consume bounded command receipts, never fetch a device or resolve XML entities. */
object DeviceInspection {
    const val MaxCharacters = 2 * 1024 * 1024
    const val MaxElements = 8192

    fun viewTree(transport: DeviceTransport, output: String): DeviceViewSnapshot {
        require(output.length <= MaxCharacters) { "View capture exceeds the 2 MiB inspection limit" }
        val elements = mutableListOf<DeviceViewElement>()
        fun append(parent: String?, depth: Int, attributes: Map<String, String>): String {
            require(elements.size < MaxElements && depth < 128) { "View hierarchy exceeds the bounded inspection limit" }
            val id = elements.size.toString()
            elements += DeviceViewElement(id, parent, depth, attributes)
            return id
        }
        when (transport) {
            DeviceTransport.Adb -> {
                val start = output.indexOf("<hierarchy")
                val end = output.lastIndexOf("</hierarchy>")
                require(start >= 0 && end >= start) { "Capture contains no complete Android hierarchy" }
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }
                val document = factory.newDocumentBuilder().parse(InputSource(StringReader(output.substring(start, end + 12))))
                fun visit(element: Element, parent: String?, depth: Int) {
                    val id = append(parent, depth, (0 until element.attributes.length).associate {
                        val attribute = element.attributes.item(it); attribute.nodeName to attribute.nodeValue
                    })
                    for (i in 0 until element.childNodes.length) (element.childNodes.item(i) as? Element)?.let { visit(it, id, depth + 1) }
                }
                val root = document.documentElement
                for (i in 0 until root.childNodes.length) (root.childNodes.item(i) as? Element)?.let { visit(it, null, 0) }
            }
            DeviceTransport.Idb -> {
                fun visit(value: JsonElement, parent: String?, depth: Int) {
                    if (value is JsonArray) { value.forEach { visit(it, parent, depth) }; return }
                    val node = value as? JsonObject ?: error("Expected an idb accessibility element")
                    val id = append(parent, depth, node.filterKeys { it != "children" }.mapValues {
                        (it.value as? JsonPrimitive)?.contentOrNull ?: it.value.toString()
                    })
                    node["children"]?.let { visit(it, id, depth + 1) }
                }
                visit(Json.parseToJsonElement(output), null, 0)
            }
            DeviceTransport.Simctl -> error("simctl does not expose an accessibility hierarchy; capture through idb")
        }
        return DeviceViewSnapshot(elements)
    }

    fun adbFiles(output: String): List<DeviceFileEntry> = output.lineSequence().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"), limit = 8)
        if (fields.size != 8 || fields[0].length != 10 || fields[0][0] !in "d-l") return@mapNotNull null
        val name = fields[7]
        if (name in setOf(".", "..")) null else DeviceFileEntry(name, fields[0][0] == 'd',
            fields[0][0] == 'l', fields[4].toLongOrNull(), fields[0], "${fields[5]} ${fields[6]}")
    }.take(4096).toList()
}

data class DeviceFileEntry(val name: String, val directory: Boolean, val symbolicLink: Boolean,
    val bytes: Long?, val permissions: String, val modified: String)
