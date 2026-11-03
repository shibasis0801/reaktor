package dev.shibasis.reaktor.blueprint.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

@OptIn(kotlin.js.ExperimentalJsExport::class)
@JsExport
fun notesBridgeRoundTripForTest(source: String, library: dynamic, edit: Boolean): String {
    val scene = importNotesDrawing(source, library)
    val placement = if (!edit) scene.placement else scene.placement.copy(capture = scene.placement.capture.map { value ->
        if (value is Capture.Box && value.id == "loose-box") value.copy(width = value.width + 16.0) else value
    })
    return exportNotesDrawing(scene, placement, library)
}

class ExcalidrawImportTest {
    private fun library(): dynamic = js("({restore:function(data){return data;},serializeAsJSON:function(elements,appState,files){return JSON.stringify({type:'excalidraw',version:2,elements:elements,appState:appState,files:files});},newElementWith:function(old,changes){return Object.assign({},old,changes,{version:old.version+1});}})")

    @Test fun notesScenesPreserveTheirStoredShapeAndUnknownMetadata() {
        val input = """{"elements":[{"id":"note-box","type":"rectangle","x":0,"y":0,"width":80,"height":40,"version":1,"customData":{"review":"pending"}}],"appState":{"gridSize":20,"future":7},"files":{},"futureNotes":{"enabled":true}}"""
        val api = library()
        val imported = importNotesDrawing(input, api)
        assertEquals(Json.parseToJsonElement(input), Json.parseToJsonElement(exportNotesDrawing(imported, imported.placement, api)))
        assertEquals(1, imported.placement.capture.size)
    }

    @Test fun editedNotesReopenWithLibraryVersionsAndKeepTheirMetadata() {
        val input = """{"elements":[{"id":"note-box","type":"rectangle","x":0,"y":0,"width":80,"height":40,"version":1}],"appState":{},"files":{},"futureNotes":true}"""
        val api = library()
        val imported = importNotesDrawing(input, api)
        val box = imported.placement.capture.single() as Capture.Box
        val exported = exportNotesDrawing(imported, imported.placement.copy(capture = listOf(box.copy(width = 96.0))), api)
        val reopened = importNotesDrawing(exported, api)
        assertEquals(96.0, (reopened.placement.capture.single() as Capture.Box).width)
        assertTrue("\"version\":2" in exported)
        assertEquals(Json.parseToJsonElement(input).jsonObject["futureNotes"], Json.parseToJsonElement(exported).jsonObject["futureNotes"])
        assertFalse("type" in Json.parseToJsonElement(exported).jsonObject)
    }

    @Test fun notesRejectWrongEnvelopesAndLossyLibraryRestores() {
        assertFailsWith<IllegalArgumentException> { importNotesDrawing("""{"type":"excalidraw","version":9,"elements":[]}""", library()) }
        assertFailsWith<IllegalArgumentException> { importNotesDrawing("""{"appState":{},"files":{}}""", library()) }
        val scene = importNotesDrawing("""{"elements":[{"id":"box","type":"rectangle","x":0,"y":0,"width":40,"height":20}],"files":{}}""", library())
        assertFailsWith<IllegalArgumentException> { exportNotesDrawing(scene, scene.placement.copy(capture = emptyList()), library()) }
    }

    @Test fun changedElementsAreVersionedByTheMaintainedLibrary() {
        val api = js("({restore:function(data){return data;},serializeAsJSON:function(elements,appState,files){return JSON.stringify({type:'excalidraw',version:2,elements:elements,appState:appState,files:files});},newElementWith:function(old,changes){return Object.assign({},old,changes,{version:old.version+1,versionNonce:99});}})")
        val input = """{"type":"excalidraw","version":2,"elements":[{"id":"box","type":"rectangle","x":0,"y":0,"width":40,"height":20,"version":1}],"files":{}}"""
        val scene = importExcalidraw(input, api)
        val box = scene.placement.capture.single() as Capture.Box
        val result = exportExcalidraw(scene, scene.placement.copy(capture = listOf(box.copy(width = 50.0))), api)
        assertTrue("\"version\":2" in result)
        assertTrue("\"versionNonce\":99" in result)
        assertEquals(50.0, (ExcalidrawScene.read(result).placement.capture.single() as Capture.Box).width)
    }

    @Test fun bridgeUsesTheHostsMaintainedRestoreAndSerializerWithoutDroppingMetadata() {
        val api = js("({restores:0, exports:0, restore:function(data){this.restores++;return data;}, serializeAsJSON:function(elements,appState,files){this.exports++;return JSON.stringify({type:'excalidraw',version:2,elements:elements,appState:appState,files:files});}})")
        val input = """{"type":"excalidraw","version":2,"elements":[{"id":"box","type":"rectangle","x":0,"y":0,"width":40,"height":20}],"appState":{"future":7},"files":{},"futureRoot":true}"""
        val scene = importExcalidraw(input, api)
        assertEquals(scene.original.toString(), exportExcalidraw(scene, scene.placement, api))
        assertEquals(2, api.restores as Int)
        assertEquals(2, api.exports as Int)
    }

    @Test fun upstreamDroppingAKnownElementOrAnAbsentLibraryRefusesTheWrite() {
        val api = js("({restore:function(data){return data;},serializeAsJSON:function(){return JSON.stringify({type:'excalidraw',version:2,elements:[]});}})")
        val input = """{"type":"excalidraw","version":2,"elements":[{"id":"box","type":"rectangle","x":0,"y":0,"width":40,"height":20}]}"""
        assertFailsWith<IllegalArgumentException> { importExcalidraw(input, api) }
        assertFailsWith<IllegalArgumentException> { importExcalidraw(input, js("({})")) }
    }
}
