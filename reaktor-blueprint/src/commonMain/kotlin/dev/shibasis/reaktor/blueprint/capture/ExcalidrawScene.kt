package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.Placement
import dev.shibasis.reaktor.blueprint.editor.Spot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.cos
import kotlin.math.sin

@Serializable
data class ExcalidrawScene(val original: JsonObject) {
    init {
        require(original["type"] == JsonPrimitive("excalidraw")) { "Expected an Excalidraw scene" }
        require(original["version"]?.jsonPrimitive?.intOrNull == 2) { "Unsupported Excalidraw scene version; keep the original drawing" }
        require(original["elements"] is JsonArray) { "Scene elements are missing" }
        val ids = elements.map { it.string("id") }
        require(ids.none(String::isBlank) && ids.toSet().size == ids.size) { "Scene element identities must be unique" }
        require(original["files"] == null || original["files"] is JsonObject) { "Scene files must be an object" }
        require(original["appState"] == null || original["appState"] is JsonObject) { "Scene appState must be an object" }
    }

    val elements: List<JsonObject> get() = original.getValue("elements").jsonArray.map { it.jsonObject }

    val placement: Placement get() {
        val files = original["files"]?.jsonObject.orEmpty()
        return Placement(capture = elements.filterNot { it["isDeleted"] == JsonPrimitive(true) }.mapNotNull(::project),
            assets = files.mapValues { (id, file) -> CaptureAsset(id, "excalidraw-file:$id", file.jsonObject.string("mimeType")) })
    }

    val preservedElements: Set<String> get() = elements.map { it.string("id") }.toSet() - placement.capture.map { it.id }.toSet()

    fun export(placement: Placement): JsonObject {
        val baseline = this.placement
        require(placement.assets == baseline.assets && placement.removedAssets.isEmpty()) { "Image files changed; refusing a lossy overwrite" }
        val next = placement.capture.associateBy { it.id }
        require(next.size == placement.capture.size && next.keys == baseline.capture.map { it.id }.toSet() && placement.removedCaptures.isEmpty()) {
            "Adding or removing imported elements needs the Excalidraw editor; keep the original drawing"
        }
        val before = baseline.capture.associateBy { it.id }
        val updated = elements.map { element ->
            val id = element.string("id")
            val capture = next[id]
            if (capture == null || capture == before[id]) element else {
                require(element.editable()) { "Element $id has bindings, a frame or unsupported metadata; refusing a lossy overwrite" }
                val anchor = capture.anchor as? CaptureAnchor.World ?: error("Element $id needs a world anchor for Excalidraw export")
                val fields = element.toMutableMap()
                fields["x"] = JsonPrimitive(anchor.at.x)
                fields["y"] = JsonPrimitive(anchor.at.y)
                when (capture) {
                    is Capture.Box -> { fields["width"] = JsonPrimitive(capture.width); fields["height"] = JsonPrimitive(capture.height); fields["angle"] = JsonPrimitive(capture.angle) }
                    is Capture.Diamond -> { fields["width"] = JsonPrimitive(capture.width); fields["height"] = JsonPrimitive(capture.height); fields["angle"] = JsonPrimitive(capture.angle) }
                    is Capture.Ellipse -> { fields["width"] = JsonPrimitive(capture.width); fields["height"] = JsonPrimitive(capture.height); fields["angle"] = JsonPrimitive(capture.angle) }
                    else -> error("Element $id cannot be changed without losing drawing information")
                }
                val result = JsonObject(fields)
                require(project(result) == capture) { "Element $id contains information Excalidraw cannot save" }
                result
            }
        }
        return JsonObject(original + ("elements" to JsonArray(updated)))
    }

    companion object {
        fun read(source: String): ExcalidrawScene = ExcalidrawScene(Json.parseToJsonElement(source).jsonObject)
    }
}

private fun JsonObject.string(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.number(key: String): Double = get(key)?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() } ?: error("Invalid element $key")
private fun JsonObject.editable(): Boolean = string("type") in setOf("rectangle", "diamond", "ellipse") &&
    listOf("containerId", "frameId", "startBinding", "endBinding", "customData").all { this[it] == null || this[it] == JsonNull } &&
    (this["boundElements"] == null || this["boundElements"] == JsonNull || this["boundElements"] == JsonArray(emptyList()))

private fun project(element: JsonObject): Capture? {
    val type = element.string("type")
    if (type !in setOf("rectangle", "diamond", "ellipse", "text", "frame", "image", "freedraw", "arrow", "line")) return null
    val id = element.string("id")
    val x = element.number("x"); val y = element.number("y")
    val width = element.number("width"); val height = element.number("height")
    require(width >= 0 && height >= 0) { "Negative drawing dimensions" }
    val angle = element["angle"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    require(angle.isFinite()) { "Invalid drawing angle" }
    val anchor = CaptureAnchor.World(Spot(x, y))
    fun points(): List<Spot> = element.getValue("points").jsonArray.map { raw ->
        val point = raw.jsonArray
        require(point.size == 2) { "Invalid drawing point" }
        val px = point[0].jsonPrimitive.double; val py = point[1].jsonPrimitive.double
        require(px.isFinite() && py.isFinite()) { "Invalid drawing point" }
        Spot(width / 2 + (px - width / 2) * cos(angle) - (py - height / 2) * sin(angle),
            height / 2 + (px - width / 2) * sin(angle) + (py - height / 2) * cos(angle))
    }
    return when (type) {
        "rectangle" -> Capture.Box(id, width, height, anchor, angle = angle)
        "diamond" -> Capture.Diamond(id, width, height, anchor, angle = angle)
        "ellipse" -> Capture.Ellipse(id, width, height, anchor, angle = angle)
        "text" -> Capture.Text(id, element.string("text"), anchor)
        "frame" -> Capture.Frame(id, element.string("name"), width, height, anchor, parent = element.string("frameId").ifBlank { null })
        "image" -> element.string("fileId").takeIf { it.isNotBlank() }?.let { Capture.Image(id, it, width, height, anchor) }
        "freedraw" -> Capture.Freehand(id, points(), anchor)
        "arrow", "line" -> {
            val points = points(); require(points.size >= 2) { "A drawing line needs two ends" }
            val from = CaptureAnchor.World(Spot(x + points.first().x, y + points.first().y))
            val to = CaptureAnchor.World(Spot(x + points.last().x, y + points.last().y))
            val bends = points.drop(1).dropLast(1).map { Spot(x + it.x, y + it.y) }
            if (type == "arrow") Capture.Arrow(id, from, to, bends) else Capture.Line(id, from, to, bends)
        }
        else -> null
    }
}
