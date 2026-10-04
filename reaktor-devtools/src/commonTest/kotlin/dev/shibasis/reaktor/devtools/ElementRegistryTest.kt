package dev.shibasis.reaktor.devtools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tree the workbench inspects, without a composition.
 *
 * Registration is what the Compose modifier does; everything interesting about the result —
 * ordering, truncation, hit-testing — is ordinary logic and is tested as such.
 */
class ElementRegistryTest {

    private fun registry(vararg elements: RegisteredElement) = ElementRegistry().apply {
        elements.forEach(::register)
    }

    private fun element(
        id: String,
        parent: String? = null,
        order: Int = 0,
        left: Float = 0f,
        top: Float = 0f,
        right: Float = 0f,
        bottom: Float = 0f,
        route: String = "",
    ) = RegisteredElement(
        id = id,
        parentId = parent,
        order = order,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        route = route,
    )

    @Test
    fun emitsParentsBeforeChildrenWithDepth() {
        val snapshot = registry(
            element("root", order = 0),
            element("child", parent = "root", order = 1),
            element("grandchild", parent = "child", order = 2),
        ).snapshot()

        assertEquals(listOf("root", "child", "grandchild"), snapshot.nodes.map { it.id })
        assertEquals(listOf(0, 1, 2), snapshot.nodes.map { it.depth })
        assertFalse(snapshot.truncated)
    }

    @Test
    fun treatsAnElementWhoseParentIsGoneAsARoot() {
        // A parent can be disposed while a child is still composed. Dropping the child would hide
        // real UI, so it is promoted rather than lost.
        val snapshot = registry(element("orphan", parent = "disposed", order = 0)).snapshot()

        assertEquals(listOf("orphan"), snapshot.nodes.map { it.id })
        assertEquals(0, snapshot.nodes.single().depth)
    }

    @Test
    fun reportsTruncationRatherThanSilentlyStopping() {
        val many = (0 until 10).map { element("node-$it", order = it) }
        val snapshot = ElementRegistry().apply { many.forEach(::register) }.snapshot(maxNodes = 4)

        assertEquals(4, snapshot.nodes.size)
        assertTrue(snapshot.truncated, "hitting the node cap is a different fact from the tree ending")
    }

    @Test
    fun scopesASnapshotToOneSubtree() {
        val snapshot = registry(
            element("a", order = 0),
            element("b", order = 1),
            element("b.child", parent = "b", order = 2),
        ).snapshot(rootId = "b")

        assertEquals(listOf("b", "b.child"), snapshot.nodes.map { it.id })
    }

    @Test
    fun hitTestResolvesToTheDeepestElementUnderThePoint() {
        // This is what a tap on a mirrored screen resolves through: the deepest element wins,
        // because that is the one the platform would have delivered the touch to.
        val found = registry(
            element("screen", order = 0, right = 400f, bottom = 800f),
            element("card", parent = "screen", order = 1, left = 20f, top = 40f, right = 380f, bottom = 200f),
            element("button", parent = "card", order = 2, left = 40f, top = 60f, right = 200f, bottom = 120f),
        ).hitTest(100f, 90f)

        assertEquals("button", found?.id)
    }

    @Test
    fun hitTestMissesOutsideEveryElement() {
        val found = registry(
            element("screen", order = 0, right = 100f, bottom = 100f),
        ).hitTest(500f, 500f)

        assertNull(found)
    }

    @Test
    fun boundsUpdateInPlaceWithoutLosingIdentity() {
        val registry = registry(element("moving", order = 0, route = "chat"))
        registry.updateBounds("moving", 10f, 20f, 110f, 220f)

        val node = registry.snapshot().nodes.single()
        assertEquals(100f, node.width)
        assertEquals(200f, node.height)
        assertEquals("chat", node.route, "the route survives a layout change")
    }

    @Test
    fun unregisteringRemovesAnElement() {
        val registry = registry(element("gone", order = 0))
        registry.unregister("gone")

        assertTrue(registry.snapshot().nodes.isEmpty())
        assertEquals(0, registry.size)
    }
}
