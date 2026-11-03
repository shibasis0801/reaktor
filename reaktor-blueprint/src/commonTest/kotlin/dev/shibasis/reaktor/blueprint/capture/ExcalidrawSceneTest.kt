package dev.shibasis.reaktor.blueprint.capture

import dev.shibasis.reaktor.blueprint.editor.Spot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlin.test.*

class ExcalidrawSceneTest {
    @Test fun boundTextFramesFilesPressureAndUnknownMetadataSurviveReopenAndExport() {
        val source = scene(listOf(
            element("frame", "frame", "\"name\":\"Review\""),
            element("box", "rectangle", "\"frameId\":\"frame\",\"boundElements\":[{\"id\":\"text\",\"type\":\"text\"}]"),
            element("text", "text", "\"text\":\"Measure coverage\",\"containerId\":\"box\",\"originalText\":\"Measure coverage\""),
            element("image", "image", "\"fileId\":\"screenshot\",\"scale\":[-1,1],\"crop\":{\"x\":1,\"width\":12}"),
            element("stroke", "freedraw", "\"points\":[[0,0],[30,10],[80,40]],\"pressures\":[0.2,0.8,0.3],\"simulatePressure\":false"),
            element("arrow", "arrow", "\"points\":[[0,0],[30,10],[80,40]],\"endBinding\":{\"elementId\":\"box\",\"focus\":0,\"gap\":1},\"endArrowhead\":\"triangle\""),
            element("future", "future-shape", "\"newField\":{\"keep\":true}"),
            element("removed", "rectangle", "\"isDeleted\":true"),
        ))
        val imported = ExcalidrawScene.read(source)
        assertEquals(6, imported.placement.capture.size)
        assertEquals(setOf("future", "removed"), imported.preservedElements)
        assertEquals("Measure coverage", (imported.placement.capture[2] as Capture.Text).text)
        assertEquals("excalidraw-file:screenshot", imported.placement.assets.getValue("screenshot").reference)
        val reopened = Json.decodeFromString<ExcalidrawScene>(Json.encodeToString(imported))
        assertEquals(Json.parseToJsonElement(source), reopened.export(reopened.placement))
        assertEquals(imported.placement, reopened.placement)
    }

    @Test fun unknownAndUnsupportedElementsRemainEvenWhenTheyHaveNoCapture() {
        val imported = ExcalidrawScene.read(scene(listOf(element("unknown", "embeddable", "\"link\":\"https://example.test/diagram\""))))
        assertTrue(imported.placement.capture.isEmpty())
        assertEquals(imported.original, imported.export(imported.placement))
    }

    @Test fun lossyBindingAuthorAssetAndRemovalEditsAreRefusedWithoutMutatingTheSource() {
        val imported = ExcalidrawScene.read(scene(listOf(element("bound", "rectangle", "\"boundElements\":[{\"id\":\"label\",\"type\":\"text\"}]"))))
        val original = imported.original
        val box = imported.placement.capture.single() as Capture.Box
        assertFailsWith<IllegalArgumentException> { imported.export(imported.placement.copy(capture = listOf(box.copy(width = 200.0)))) }
        assertFailsWith<IllegalArgumentException> { imported.export(imported.placement.copy(capture = emptyList())) }
        assertFailsWith<IllegalArgumentException> { imported.export(imported.placement.copy(assets = emptyMap())) }
        val plain = ExcalidrawScene.read(scene(listOf(element("plain", "rectangle"))))
        val plainBox = plain.placement.capture.single() as Capture.Box
        assertFailsWith<IllegalArgumentException> { plain.export(plain.placement.copy(capture = listOf(plainBox.copy(author = CaptureAuthor("(codex, xhigh, designer)"))))) }
        assertEquals(original, imported.original)
    }

    @Test fun unboundGeometryEditsKeepAllUnchangedSceneAndElementFields() {
        val imported = ExcalidrawScene.read(scene(listOf(element("plain", "diamond", "\"strokeColor\":\"black\",\"seed\":17"))))
        val shape = imported.placement.capture.single() as Capture.Diamond
        val next = imported.placement.copy(capture = listOf(shape.copy(width = 160.0, anchor = CaptureAnchor.World(Spot(20.0, 30.0)))))
        val exported = imported.export(next)
        assertEquals(JsonPrimitive(17), exported.getValue("elements").jsonArray.single().jsonObject["seed"])
        assertEquals(imported.original["futureRoot"], exported["futureRoot"])
        assertEquals(next.capture, ExcalidrawScene(exported).placement.capture)
    }

    @Test fun localArrowPointsBecomeWorldEndsAndBendsWithoutInventingTopology() {
        val imported = ExcalidrawScene.read(scene(listOf(element("arrow", "arrow", "\"points\":[[5,7],[30,10],[80,40]]"))))
        val arrow = imported.placement.capture.single() as Capture.Arrow
        assertEquals(CaptureAnchor.World(Spot(15.0, 27.0)), arrow.anchor)
        assertEquals(CaptureAnchor.World(Spot(90.0, 60.0)), arrow.end)
        assertEquals(listOf(Spot(40.0, 30.0)), arrow.bends)
        assertEquals(imported.original, imported.export(imported.placement))
    }

    @Test fun invalidVersionsDuplicateIdsAndNonFiniteGeometryAreRefused() {
        assertFailsWith<IllegalArgumentException> { ExcalidrawScene.read(scene(emptyList()).replace("\"version\":2", "\"version\":3")) }
        assertFailsWith<IllegalArgumentException> { ExcalidrawScene.read(scene(listOf(element("a", "rectangle"), element("a", "rectangle")))) }
        assertFails { ExcalidrawScene.read(scene(listOf(element("a", "rectangle", "\"width\":\"NaN\"")))).placement }
    }

    private fun scene(elements: List<String>) = """{"type":"excalidraw","version":2,"source":"local-test","elements":[${elements.joinToString(",")}],"appState":{"viewBackgroundColor":"white","futureCamera":{"keep":true}},"files":{"screenshot":{"id":"screenshot","mimeType":"image/png","dataURL":"data:image/png;base64,AA==","created":1,"futureFile":7}},"futureRoot":{"keep":[1,2,3]}}"""
    private fun element(id: String, type: String, more: String = "") = """{"id":"$id","type":"$type","x":10,"y":20,"width":80,"height":40,"angle":0${if (more.isBlank()) "" else ",$more"}}"""
}
