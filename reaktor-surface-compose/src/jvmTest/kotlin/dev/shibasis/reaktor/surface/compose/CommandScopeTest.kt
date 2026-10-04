@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CommandScopeTest {
    private val keys = platformKeyConvention()
    private val primary = if (keys == KeyConvention.Mac) Key.MetaLeft else Key.CtrlLeft
    private val drawer = Command(CommandId("toggle-drawer"), "Show or hide the drawer", Chord.Of(KeyName.J, primary = true))
    private val search = Command(CommandId("search"), "Search", Chord.Of(KeyName.K, primary = true))
    private val find = Chord.Of(KeyName.F, primary = true)

    private fun commandsOf(vararg commands: Command) = CommandSet(commands.toList())

    private fun primaryEvent(key: Key) = KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = keys == KeyConvention.Pc, isMetaPressed = keys == KeyConvention.Mac)

    @Test
    fun theInnermostFocusedScopeWins() = runComposeUiTest {
        val invoked = mutableListOf<String>()
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Box(Modifier.commands(commandsOf(Command(CommandId("window-find"), "Find", find))) { invoked += it.value }) {
                    Column(Modifier.commands(commandsOf(Command(CommandId("pane-find"), "Find in pane", find))) { invoked += it.value }) {
                        Button({}, Modifier.testTag("inside")) { BasicText("Inside") }
                    }
                }
            }
        }
        onNodeWithTag("inside").requestFocus()
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.F) } }
        assertEquals(listOf("pane-find"), invoked)
    }

    @Test
    fun aFocusedTextFieldKeepsItsEditingKeys() = runComposeUiTest {
        val wordModifier = if (keys == KeyConvention.Mac) Key.AltLeft else Key.CtrlLeft
        val wordLeft = if (keys == KeyConvention.Mac) Chord.Of(KeyName.Left, alt = true) else Chord.Of(KeyName.Left, control = true)
        val invoked = mutableListOf<String>()
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                Column(Modifier.commands(commandsOf(Command(CommandId("back"), "Back", wordLeft))) { invoked += it.value }) {
                    BasicTextField(TextFieldState("one two"), Modifier.testTag("field"))
                    Button({}, Modifier.testTag("button")) { BasicText("Button") }
                }
            }
        }
        onNodeWithTag("field").requestFocus()
        onRoot().performKeyInput { withKeyDown(wordModifier) { pressKey(Key.DirectionLeft) } }
        assertTrue(invoked.isEmpty(), "the field moves by word and the scope never sees the key")
        onNodeWithTag("button").requestFocus()
        onRoot().performKeyInput { withKeyDown(wordModifier) { pressKey(Key.DirectionLeft) } }
        assertEquals(listOf("back"), invoked)
    }

    @Test
    fun anUnavailableChordIsConsumedWithoutInvokingAnything() = runComposeUiTest {
        val save = Chord.Of(KeyName.S, primary = true)
        val invoked = mutableListOf<String>()
        val unhandled = mutableListOf<Key>()
        setContent {
            Box(Modifier.onKeyEvent { if (it.type == KeyEventType.KeyDown) unhandled += it.key; false }) {
                SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                    Box(Modifier.commands(commandsOf(Command(CommandId("save-all"), "Save all", save))) { invoked += it.value }) {
                        Column(Modifier.commands(commandsOf(Command(CommandId("save"), "Save", save, availability = Availability.Unavailable("Read-only")))) { invoked += it.value }) {
                            Button({}, Modifier.testTag("inside")) { BasicText("Inside") }
                        }
                    }
                }
            }
        }
        onNodeWithTag("inside").requestFocus()
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.S) } }
        assertTrue(invoked.isEmpty())
        assertFalse(Key.S in unhandled)
    }

    @Test
    fun theWindowFallbackReachesOnlyTheRootScopeWhenNothingIsFocused() = runComposeUiTest {
        var host: CommandHost? = null
        val invoked = mutableListOf<String>()
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                host = LocalCommandHost.current
                Box(Modifier.commands(commandsOf(drawer)) { invoked += "root:" + it.value }) {
                    Column(Modifier.commands(commandsOf(drawer)) { invoked += "pane:" + it.value }) {
                        Button({}, Modifier.testTag("inside")) { BasicText("Inside") }
                    }
                }
            }
        }
        assertTrue(runOnIdle { requireNotNull(host).dispatch(primaryEvent(Key.J)) })
        assertFalse(runOnIdle { requireNotNull(host).dispatch(primaryEvent(Key.Q)) })
        assertEquals(listOf("root:toggle-drawer"), invoked)
    }

    @Test
    fun aChordPressedInAnOverlayReachesTheRootScopeOnceThroughTheWindowFallback() = runComposeUiTest {
        var host: CommandHost? = null
        var open by mutableStateOf(false)
        val invoked = mutableListOf<String>()
        setContent {
            Box(Modifier.onKeyEvent { event -> requireNotNull(host).dispatch(event) }) {
                SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                    host = LocalCommandHost.current
                    Box(Modifier.commands(commandsOf(drawer)) { invoked += it.value }) {
                        Menu(open, { open = it }) {
                            Trigger(Modifier.testTag("actions")) { BasicText("Actions") }
                            Popup { Item("copy", {}, Modifier.testTag("copy")) { BasicText("Copy") } }
                        }
                    }
                }
            }
        }
        onNodeWithTag("actions").requestFocus()
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.J) } }
        assertEquals(listOf("toggle-drawer"), invoked)
        onNodeWithTag("actions").performClick()
        onNodeWithTag("copy").assertIsFocused()
        onRoot().performKeyInput { withKeyDown(primary) { pressKey(Key.J) } }
        assertEquals(listOf("toggle-drawer", "toggle-drawer"), invoked)
    }

    @Test
    fun theHostListsTheFocusPathInnermostFirstThenTheRoot() = runComposeUiTest {
        var host: CommandHost? = null
        var focus: FocusManager? = null
        val logs = Command(CommandId("logs-find"), "Find in logs", find)
        val network = Command(CommandId("network-clear"), "Clear", Chord.Of(KeyName.Delete, primary = true), availability = Availability.Unavailable("Nothing to clear"))
        val invoked = mutableListOf<String>()
        setContent {
            SurfaceEnvironmentProvider(SurfaceEnvironment()) {
                host = LocalCommandHost.current
                focus = LocalFocusManager.current
                Box(Modifier.commands(commandsOf(drawer, search)) { invoked += it.value }) {
                    Column {
                        Box(Modifier.commands(commandsOf(logs)) { invoked += it.value }) { Button({}, Modifier.testTag("logs")) { BasicText("Logs") } }
                        Box(Modifier.commands(commandsOf(network)) { invoked += it.value }) { Button({}, Modifier.testTag("network")) { BasicText("Network") } }
                    }
                }
            }
        }
        onNodeWithTag("logs").requestFocus()
        assertEquals(listOf("logs-find", "toggle-drawer", "search"), runOnIdle { requireNotNull(host).focused.map { it.command.id.value } })
        runOnIdle { requireNotNull(focus).clearFocus() }
        assertEquals(listOf("toggle-drawer", "search"), runOnIdle { requireNotNull(host).focused.map { it.command.id.value } })
        assertEquals(network, runOnIdle { requireNotNull(host).command(network.id) })
        assertNull(runOnIdle { requireNotNull(host).command(CommandId("unknown")) })
        assertFalse(runOnIdle { requireNotNull(host).invoke(network.id) })
        assertTrue(runOnIdle { requireNotNull(host).invoke(logs.id) })
        assertEquals(listOf("logs-find"), invoked)
    }

    @Test
    fun theNativeBarGetsTheSameChord() {
        val fullScreen = Chord.Platform(mac = Chord.Of(KeyName.F, primary = true, control = true), pc = Chord.Of(KeyName.F11))
        assertEquals(KeyShortcut(Key.F, ctrl = true, meta = true), fullScreen.keyShortcut(KeyConvention.Mac))
        assertEquals(KeyShortcut(Key.F11), fullScreen.keyShortcut(KeyConvention.Pc))
        assertEquals(KeyShortcut(Key.K, meta = true, shift = true), Chord.Of(KeyName.K, primary = true, shift = true).keyShortcut(KeyConvention.Mac))
        assertEquals(KeyShortcut(Key.K, ctrl = true, shift = true), Chord.Of(KeyName.K, primary = true, shift = true).keyShortcut(KeyConvention.Pc))
        assertEquals(KeyShortcut(Key.One, meta = true), Chord.Of(KeyName.Digit1, primary = true).keyShortcut(KeyConvention.Mac))
        assertNull(Chord.Platform(mac = null, pc = Chord.Of(KeyName.F11)).keyShortcut(KeyConvention.Mac))
    }
}
