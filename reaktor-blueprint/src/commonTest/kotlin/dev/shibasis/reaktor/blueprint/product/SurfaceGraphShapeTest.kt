package dev.shibasis.reaktor.blueprint.product

import dev.shibasis.reaktor.blueprint.BlueprintEngine
import dev.shibasis.reaktor.blueprint.BlueprintFace
import dev.shibasis.reaktor.blueprint.editor.id
import dev.shibasis.reaktor.blueprint.graph.AppGraph
import dev.shibasis.reaktor.blueprint.graph.AppGraphLayout
import dev.shibasis.reaktor.blueprint.graph.Detail
import dev.shibasis.reaktor.blueprint.graph.LayoutRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurfaceGraphShapeTest {
    private val nodes = listOf(
        SurfaceGraphNode("source", "Source", "Book", rows = listOf(SurfaceGraphRow("source-row", "First", key = "first"))),
        SurfaceGraphNode("target", "Target", "Book", rows = listOf(SurfaceGraphRow("target-row", "Second", key = "second"))))

    @Test
    fun originalWireIdentityAndFamilySurviveRowProjection() {
        val document = SurfaceGraphDocument(nodes, listOf(SurfaceGraphLink("recorded-link", "source-row", "target-row")), emptyList(), linkFamily = { "chapter" })
        val wire = document.shape.wires.single()
        assertEquals("recorded-link", wire.id)
        assertEquals("recorded-link", wire.sourceId)
        assertEquals("recorded-link", wire.definitionId)
        assertEquals("chapter", document.family(wire))
        assertEquals("source", wire.provider)
        assertEquals("target", wire.consumer)
        assertEquals("first", wire.providerPort)
        assertEquals("second", wire.consumerPort)
        assertTrue(document.shape.nodes.single { it.id == wire.provider }.ports.any { it.key == wire.providerPort && it.provides })
        assertTrue(document.shape.nodes.single { it.id == wire.consumer }.ports.any { it.key == wire.consumerPort && !it.provides })
    }

    @Test
    fun endpointDeduplicationKeepsTheOriginalFirstLink() {
        val document = SurfaceGraphDocument(nodes, listOf(
            SurfaceGraphLink("first-link", "source-row", "target-row"),
            SurfaceGraphLink("second-link", "source-row", "target-row")), emptyList())
        assertEquals(listOf("first-link"), document.shape.wires.map { it.id })
    }

    @Test
    fun portlessSourceUsesProseGeometryWithSpaceForItsPaintedTitle() {
        val document = SurfaceGraphDocument(
            listOf(SurfaceGraphNode("book", "A complete source title", "Book · 12 chapters")),
            emptyList(), emptyList())
        assertEquals("Prose", document.shape.nodes.single().attributes[BlueprintFace.Key])
        val card = AppGraphLayout.layout(AppGraph(document.shape), LayoutRequest(Detail.Ports)).cards.getValue("book")
        assertTrue(card.height >= 144.0)
        assertTrue(card.height - BlueprintEngine.ProseFooterHeight >= BlueprintEngine.ProseHeaderHeight - 6.0)
        assertEquals("Code", SurfaceGraphDocument(nodes, emptyList(), emptyList())
            .shape.nodes.first().attributes[BlueprintFace.Key])
    }

    @Test
    fun missingAndSelfEndpointsRemainExcluded() {
        val document = SurfaceGraphDocument(nodes, listOf(
            SurfaceGraphLink("missing", "source-row", "absent"),
            SurfaceGraphLink("self", "source-row", "source-row")), emptyList())
        assertTrue(document.shape.wires.isEmpty())
    }
}
