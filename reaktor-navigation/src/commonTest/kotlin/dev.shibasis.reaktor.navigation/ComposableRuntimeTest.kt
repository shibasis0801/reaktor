package dev.shibasis.reaktor.navigation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull


@OptIn(ExperimentalCoroutinesApi::class)
class ComposableRuntimeTest {
    @Test
    fun `tree updates reactively to state changes`() = runTest {
        val root = Node("root")
        val applier = TreeApplier(root)

        withContext(BroadcastFrameClock()) {
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(applier, recomposer)

            val dynamicValue = mutableStateOf("Initial Value")
            val showNode = mutableStateOf(true)

            composition.setContent {
                TreeNode("Static Parent") {
                    TreeNode("Child A")
                    TreeNode(
                        name = "Reactive Child B",
                        value = dynamicValue.value
                    )
                    if (showNode.value) {
                        TreeNode("Conditional Child C")
                    }
                }
            }

            val recomposerJob = launch {
                recomposer.runRecomposeAndApplyChanges()
            }

            // --- Initial State Assertion ---
            advanceUntilIdle() // Ensure initial composition runs
            val staticParent = root.children.first()
            assertEquals(3, staticParent.children.size)
            assertEquals("Initial Value", staticParent.children[1].value)
            assertNotNull(staticParent.children.find { it.name == "Conditional Child C" })

            // --- 1. Update a value ---
            dynamicValue.value = "Updated!"
            advanceUntilIdle()
            assertEquals("Updated!", staticParent.children[1].value)

            // --- 2. Remove a node ---
            showNode.value = false
            advanceUntilIdle()
            assertEquals(2, staticParent.children.size)
            assertNull(staticParent.children.find { it.name == "Conditional Child C" })

            recomposerJob.cancel()
            composition.dispose()
        }
    }
}


class Node(val name: String) {
    var value: String? = null
    val children = mutableListOf<Node>()
    var parent: Node? = null

    fun toTreeString(indent: String = ""): String {
        val builder = StringBuilder("$indent- $name${value?.let { " [value=$it]" } ?: ""}\n")
        for (child in children) {
            builder.append(child.toTreeString(indent + "  "))
        }
        return builder.toString()
    }

    override fun toString(): String {
        return toTreeString()
    }
}

// TreeApplier.kt
class TreeApplier(root: Node) : AbstractApplier<Node>(root) {
    override fun insertTopDown(index: Int, instance: Node) {
        val currentParent = current
        currentParent.children.add(index, instance)
        instance.parent = currentParent
    }

    override fun insertBottomUp(index: Int, instance: Node) {
    }

    override fun remove(index: Int, count: Int) {
        current.children.removeRange(index, count)
    }

    override fun move(from: Int, to: Int, count: Int) {
        current.children.move(from, to, count)
    }

    override fun onClear() {
        root.children.clear()
    }
}

private fun <T> MutableList<T>.removeRange(fromIndex: Int, toIndex: Int) {
    for (i in toIndex - 1 downTo fromIndex) {
        removeAt(i)
    }
}

private fun <T> MutableList<T>.move(from: Int, to: Int, count: Int) {
    val destination = if (from > to) to else to - count
    val sublist = subList(from, from + count)
    val moving = sublist.toMutableList()
    sublist.clear()
    addAll(destination, moving)
}

@Composable
fun TreeNode(
    name: String,
    value: String? = null,
    content: @Composable () -> Unit = {}
) {
    ComposeNode<Node, TreeApplier>(
        factory = { Node(name) },
        update = {
            set(value) { this.value = it }
        },
        content = content
    )
}





