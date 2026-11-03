package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.Placement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

fun importNotesDrawing(source: String, library: dynamic): ExcalidrawScene {
    val original = Json.parseToJsonElement(source).jsonObject
    require("type" !in original && "version" !in original) { "Expected a Notes drawing; import Excalidraw files through the file importer" }
    return importExcalidraw(JsonObject(original + mapOf("type" to JsonPrimitive("excalidraw"), "version" to JsonPrimitive(2))).toString(), library)
}

fun exportNotesDrawing(scene: ExcalidrawScene, placement: Placement, library: dynamic): String =
    JsonObject(Json.parseToJsonElement(exportExcalidraw(scene, placement, library)).jsonObject - "type" - "version").toString()

fun importExcalidraw(source: String, library: dynamic): ExcalidrawScene {
    val scene = ExcalidrawScene.read(source)
    validateExcalidraw(source, library)
    return scene
}

fun exportExcalidraw(scene: ExcalidrawScene, placement: Placement, library: dynamic): String {
    val proposed = scene.export(placement)
    val before = scene.elements.associateBy { it.getValue("id") }
    val elements = ExcalidrawScene(proposed).elements.map { element ->
        val old = before.getValue(element.getValue("id"))
        if (old == element) element else {
            require(jsTypeOf(library.newElementWith) == "function") { "Excalidraw must version the changed element; keep the original drawing" }
            val changes = JsonObject(element.filter { (key, value) -> old[key] != value })
            val versioned = library.newElementWith(JSON.parse<dynamic>(old.toString()), JSON.parse<dynamic>(changes.toString()))
            Json.parseToJsonElement(JSON.stringify(versioned)).jsonObject
        }
    }
    val result = JsonObject(proposed + ("elements" to JsonArray(elements))).toString()
    validateExcalidraw(result, library)
    return result
}

private fun validateExcalidraw(source: String, library: dynamic) {
    require(jsTypeOf(library.restore) == "function" && jsTypeOf(library.serializeAsJSON) == "function") { "The host must supply its installed Excalidraw library" }
    val data = JSON.parse<dynamic>(source)
    val options = js("({refreshDimensions: false, repairBindings: false})")
    val restored = library.restore(data, null, null, options)
    val serialized = library.serializeAsJSON(restored.elements, restored.appState, restored.files, "local") as String
    val checked = ExcalidrawScene(Json.parseToJsonElement(serialized).jsonObject)
    val expected = ExcalidrawScene.read(source).placement.capture.map { it.id }.toSet()
    require(checked.elements.map { it["id"].toString().trim('"') }.containsAll(expected)) { "Excalidraw cannot retain these elements; keep the original drawing" }
}
