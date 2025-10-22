package dev.shibasis.reaktor.navigation

import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import dev.shibasis.reaktor.navigation.util.ObservableStack
import kotlin.test.*

class ObservableStackTest {

    @Test
    fun `initializes with a null top when no initial value is provided`() = runTest {
        val observableStack = ObservableStack<Int>()
        assertTrue(observableStack.isEmpty())
        assertEquals(0, observableStack.size)
        assertNull(observableStack.top.value)
    }

    @Test
    fun `initializes with a value when provided`() = runTest {
        val observableStack = ObservableStack(1)
        assertFalse(observableStack.isEmpty())
        assertEquals(1, observableStack.size)
        assertEquals(1, observableStack.top.value)
    }

    @Test
    fun `push updates top and increases size`() = runTest {
        val observableStack = ObservableStack(1)
        observableStack.top.test {
            assertEquals(1, awaitItem()) // Initial value

            observableStack.push(2)
            assertEquals(2, awaitItem())
            assertEquals(2, observableStack.size)
            assertFalse(observableStack.isEmpty())

            observableStack.push(3)
            assertEquals(3, awaitItem())
            assertEquals(3, observableStack.size)
        }
    }

    @Test
    fun `pop updates top decreases size and returns true when stack is not empty`() = runTest {
        val observableStack = ObservableStack(1)
        observableStack.push(2)

        observableStack.top.test {
            assertEquals(2, awaitItem()) // Initial value after push

            val result = observableStack.pop()
            assertTrue(result)
            assertEquals(1, awaitItem())
            assertEquals(1, observableStack.size)
        }
    }

    @Test
    fun `pop on the last item clears the stack and sets top to null`() = runTest {
        val observableStack = ObservableStack(1)
        observableStack.top.test {
            assertEquals(1, awaitItem()) // Initial value

            val result = observableStack.pop()
            assertTrue(result)
            assertNull(awaitItem())
            assertEquals(0, observableStack.size)
            assertTrue(observableStack.isEmpty())
        }
    }

    @Test
    fun `pop returns false and does nothing when stack is empty`() = runTest {
        val observableStack = ObservableStack<Int>()
        observableStack.top.test {
            assertNull(awaitItem()) // Initial null value

            val result = observableStack.pop()
            assertFalse(result)
            assertEquals(0, observableStack.size)
            assertTrue(observableStack.isEmpty())

            // Ensure no new items were emitted
            expectNoEvents()
        }
    }

    @Test
    fun `replace correctly pops and pushes updating the top value`() = runTest {
        val observableStack = ObservableStack(10)
        observableStack.push(20)

        observableStack.top.test {
            assertEquals(20, awaitItem()) // Initial value

            observableStack.replace(30)
            assertEquals(10, awaitItem()) // Top after pop
            assertEquals(30, awaitItem()) // Top after push
            assertEquals(2, observableStack.size)
        }
    }

    @Test
    fun `replace on an empty stack just pushes the new value`() = runTest {
        val observableStack = ObservableStack<Int>()
        observableStack.top.test {
            // 1. The initial value is null, as expected.
            assertNull(awaitItem())

            // 2. Call replace. This internally calls pop() (which does nothing),
            //    then push(100), which emits 100.
            observableStack.replace(100)

            // 3. The next and ONLY new item in the flow is 100.
            assertEquals(100, awaitItem())
            assertEquals(1, observableStack.size)
        }
    }

    @Test
    fun `clear removes all items and sets top to null`() = runTest {
        val observableStack = ObservableStack(1)
        observableStack.push(2)
        observableStack.push(3)

        observableStack.top.test {
            assertEquals(3, awaitItem()) // Initial value

            observableStack.clear()
            assertNull(awaitItem())
            assertEquals(0, observableStack.size)
            assertTrue(observableStack.isEmpty())
        }
    }

    @Test
    fun `clear does nothing on an empty stack`() = runTest {
        val observableStack = ObservableStack<Int>()
        observableStack.clear()
        assertTrue(observableStack.isEmpty())
        assertNull(observableStack.top.value)
    }

    @Test
    fun `entries property reflects the underlying stack`() {
        val observableStack = ObservableStack(10)
        observableStack.push(20)
        observableStack.push(30)

        val entries = observableStack.entries
        assertEquals(listOf(10, 20, 30), entries)
        assertEquals(3, entries.size)

        observableStack.pop()
        assertEquals(listOf(10, 20), observableStack.entries)
    }
}