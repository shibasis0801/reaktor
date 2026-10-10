@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.composeflow.compose

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportCompositionTest {
    @Test
    fun cullingPreservesRememberedStateAndLifetimeOfRemainingNodes() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            failure = runCatching {
                val state = ReactFlowState()
                val owners = mutableMapOf<String, String>()
                val disposed = mutableSetOf<String>()
                val nodes = listOf("a", "b", "c").mapIndexed { index, id ->
                    Node(id, XYPosition(index * 100.0, 20.0), type = "probe", width = 50.0, height = 50.0)
                }
                val scene = ImageComposeScene(320, 200, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    ReactFlow(nodes, emptyList(), state = state, fitView = false, showControls = false,
                        nodeTypes = mapOf("probe" to { props ->
                            val owner = remember { props.id }
                            owners[props.id] = owner
                            DisposableEffect(Unit) { onDispose { disposed += owner } }
                            Text(owner)
                        }))
                }
                fun render() { repeat(10) { Snapshot.sendApplyNotifications(); scene.render().close() } }
                try {
                    render()
                    assertEquals(mapOf("a" to "a", "b" to "b", "c" to "c"), owners)
                    state.panBy(-120.0, 0.0)
                    render()
                    assertTrue("a" in disposed)
                    assertTrue("b" !in disposed && "c" !in disposed)
                    assertTrue(owners.all { (id, owner) -> id == owner }, owners.toString())
                    state.panBy(120.0, 0.0)
                    render()
                    assertEquals(mapOf("a" to "a", "b" to "b", "c" to "c"), owners)
                    assertTrue("b" !in disposed && "c" !in disposed)
                } finally { scene.close() }
            }.exceptionOrNull()
        }
        failure?.let { throw it }
    }
}
