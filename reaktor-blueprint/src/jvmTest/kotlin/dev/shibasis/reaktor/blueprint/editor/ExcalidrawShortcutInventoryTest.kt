package dev.shibasis.reaktor.blueprint.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import dev.shibasis.reaktor.surface.find
import dev.shibasis.reaktor.surface.CommandId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExcalidrawShortcutInventoryTest {
    private data class Shortcut(val action: String, val keys: List<String>)

    private fun editor(): GraphEditor {
        val shape = GraphShape(listOf(ScopeShape("app", "App")),
            listOf(NodeShape("a", "app", "A", "A", NodeKind.Service)), emptyList(), emptyList())
        return GraphEditor(shape, Placement(mapOf("a" to Spot(0.0, 0.0))),
            catalogue = { listOf(Template("Service", NodeKind.Service, "Service", emptyList())) }).also { editor ->
            editor.permits = { _, _ -> true }
            editor.validate = { Verdict(true, "") }
            editor.commit = { _, expected -> EditReceipt.Accepted(expected.copy(source = expected.source + "+")) }
            editor.savePlacement = { _, expected -> EditReceipt.Accepted(expected.copy(presentation = expected.presentation + "+")) }
        }
    }

    @Test
    fun plainArrowMovesTheSelectedObjectAndOneUndoRestoresItsPlacement() {
        for (convention in KeyConvention.entries) {
            val editor = editor()
            editor.select(Selection(setOf("a")))
            val before = editor.placement
            val x = editor.layout.cards.getValue("a").x
            editor.invoke(assertNotNull(editor.commands.find(KeyStroke(KeyName.Left), convention)).id)
            assertTrue(editor.layout.cards.getValue("a").x < x, "$convention Left must nudge the selected card")
            assertEquals(1, editor.history.size)
            editor.undo()
            assertEquals(before, editor.placement)
        }
    }

    @Test
    fun rectangleKeyDrawsACaptureWithoutCreatingADomainNode() {
        for (convention in KeyConvention.entries) {
            val editor = editor()
            val before = editor.shape
            editor.invoke(assertNotNull(editor.commands.find(KeyStroke(KeyName.R), convention)).id)
            editor.press(700f, 400f, Offset.Zero, 4f, PointerKeyboardModifiers(), 1, false)
            editor.drag(900f, 550f, Offset.Zero)
            editor.release(900f, 550f, Offset.Zero)
            assertEquals(before, editor.shape, "$convention R must retain Excalidraw's capture meaning")
            assertEquals(1, editor.placement.captures.size)
            assertEquals(1, editor.history.size)
        }
    }

    @Test
    fun oneKeyStartsEnterCommitsEscapeCancelsAndTheNextKeyWorks() {
        for (convention in KeyConvention.entries) {
            val editor = editor()
            fun key(name: KeyName) = editor.invoke(assertNotNull(editor.commands.find(KeyStroke(name), convention), "$convention $name").id)
            val shape = editor.shape
            val revision = editor.editRevision
            key(KeyName.R)
            assertTrue(editor.capturePreview.isNotEmpty())
            assertTrue(editor.placement.capture.isEmpty())
            key(KeyName.Enter)
            assertEquals(1, editor.placement.capture.size)
            assertEquals(1, editor.history.size)
            assertTrue(editor.editRevision != revision)
            assertEquals(EditorTool.Select, editor.tool)
            key(KeyName.O)
            key(KeyName.Escape)
            assertEquals(1, editor.placement.capture.size)
            assertEquals(1, editor.history.size)
            key(KeyName.D)
            key(KeyName.Enter)
            assertEquals(2, editor.placement.capture.size)
            assertEquals(2, editor.history.size)
            assertEquals(shape, editor.shape)
            editor.undo()
            assertEquals(1, editor.placement.capture.size)
            editor.redo()
            assertEquals(2, editor.placement.capture.size)
        }
    }

    @Test
    fun asynchronousCaptureRefusalKeepsTheCompleteAcceptedStateAndBothStacks() = runBlocking {
        val editor = editor()
        val receipt = CompletableDeferred<EditReceipt>()
        editor.editScope = CoroutineScope(Dispatchers.Unconfined)
        editor.savePlacement = { _, _ -> receipt.await() }
        val shape = editor.shape
        val placement = editor.placement
        val revision = editor.editRevision
        val history = editor.history.toList()
        val future = editor.future.toList()
        editor.invoke(CommandId("tool-box"))
        editor.invoke(CommandId("edit-rename-enter"))
        assertNotNull(editor.pending)
        editor.invoke(CommandId("tool-ellipse"))
        assertEquals(shape, editor.shape)
        assertEquals(placement, editor.placement)
        receipt.complete(EditReceipt.Refused("Fixture refusal"))
        assertEquals(shape, editor.shape)
        assertEquals(placement, editor.placement)
        assertEquals(revision, editor.editRevision)
        assertEquals(history, editor.history)
        assertEquals(future, editor.future)
        assertTrue(editor.capturePreview.isEmpty())
    }

    private val sources by lazy {
        val roots = buildList {
            System.getProperty("excalidraw.dist")?.let { add(File(it)) }
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.forEach { root ->
                add(File(root, "bestbuds/node_modules/.pnpm"))
                add(File(root, "node_modules/.pnpm"))
            }
            add(File(System.getProperty("user.home"), "dev/bestbuds/node_modules/.pnpm"))
        }
        val dist = roots.firstNotNullOfOrNull { root ->
            if (root.name == "dev" && root.listFiles()?.any { it.name.endsWith(".js.map") } == true) root
            else root.listFiles()?.firstOrNull { it.name.startsWith("@excalidraw+excalidraw@0.18.1_") }
                ?.resolve("node_modules/@excalidraw/excalidraw/dist/dev")?.takeIf { it.isDirectory }
        } ?: error("Installed Excalidraw 0.18.1 source maps are required; set excalidraw.dist to dist/dev")
        dist.listFiles().orEmpty().filter { it.name.endsWith(".js.map") }.flatMap { map ->
            val json = Json.parseToJsonElement(map.readText()).jsonObject
            json.getValue("sources").jsonArray.zip(json.getValue("sourcesContent").jsonArray).map { (path, source) ->
                path.jsonPrimitive.content to source.jsonPrimitive.content
            }
        }.toMap()
    }

    private fun source(suffix: String) = sources.entries.single { it.key.endsWith(suffix) }.value

    private fun shortcuts(convention: KeyConvention): List<Shortcut> {
        val table = source("actions/shortcuts.ts").substringAfter("const shortcutMap:").substringAfter("= {").substringBefore("\n};")
        return Regex("(?ms)^  (\\w+): \\[(.*?)\\],").findAll(table).map { match ->
            val body = match.groupValues[2]
            val keys = Regex("getShortcutKey\\(\"([^\"]+)\"\\)").findAll(body).map { it.groupValues[1] }.toList()
            Shortcut(match.groupValues[1], if ("isDarwin" in body) listOf(keys[if (convention == KeyConvention.Mac) 0 else 1]) else keys)
        }.toList()
    }

    private fun stroke(text: String, convention: KeyConvention): KeyStroke? {
        val parts = text.split('+')
        val symbol = if (text.endsWith("++")) "+" else parts.last()
        val key = when (symbol) {
            "+" -> KeyName.Equals
            "-" -> KeyName.Minus
            "/", "?" -> KeyName.Slash
            "[" -> KeyName.LeftBracket
            "]" -> KeyName.RightBracket
            "'" -> KeyName.Apostrophe
            in "0".."9" -> KeyName.valueOf("Digit$symbol")
            else -> KeyName.entries.firstOrNull { it.name.equals(symbol, ignoreCase = true) }
        } ?: return null
        val primary = "CtrlOrCmd" in parts
        return KeyStroke(key, shift = "Shift" in parts || symbol == "+" || symbol == "?", alt = "Alt" in parts,
            control = primary && convention == KeyConvention.Pc, meta = primary && convention == KeyConvention.Mac)
    }

    @Test
    fun installedTablesIncludeAllRegisteredActionsAndPlatformOrderingDefaults() {
        val registered = sources.values.flatMap { source ->
            Regex("register(?:<[^>]+>)?\\(\\{\\s*name:\\s*[\"']([^\"']+)[\"']").findAll(source).map { it.groupValues[1] }.toList()
        }.toSet()
        val history = Regex("name:\\s*[\"']([^\"']+)[\"']").findAll(source("actions/actionHistory.tsx")).map { it.groupValues[1] }.toSet()
        val actions = registered + history
        assertEquals(93, actions.size)
        assertTrue(actions.containsAll(listOf("undo", "redo", "cropEditor", "toggleLinearEditor", "autoResize", "setEmbeddableAsActiveTool")))
        for (convention in KeyConvention.entries) {
            val table = shortcuts(convention).associate { it.action to it.keys }
            assertEquals(listOf("CtrlOrCmd+/", "CtrlOrCmd+Shift+P"), table["commandPalette"])
            assertEquals(listOf("Shift+3"), table["zoomToFitSelection"])
            assertEquals(listOf(if (convention == KeyConvention.Mac) "CtrlOrCmd+Alt+[" else "CtrlOrCmd+Shift+["), table["sendToBack"])
            assertEquals(listOf("CtrlOrCmd+'"), table["gridMode"])
            assertEquals(KeyStroke(KeyName.Slash, shift = true), stroke("?", convention))
        }
        assertTrue("KEYS.X" in source("shapes.tsx"))
        assertTrue("CtrlOrCmd+Enter" in source("components/HelpDialog.tsx"))
    }

    @Test
    fun everyAdvertisedShortcutResolvesToItsCanonicalCommandOnMacAndPc() {
        val commands = mapOf(
            "toggleTheme" to setOf("edit-theme"), "saveScene" to setOf("edit-save"),
            "loadScene" to setOf("edit-load"), "clearCanvas" to setOf("edit-clear"),
            "imageExport" to setOf("edit-export"), "commandPalette" to setOf("edit-palette", "edit-palette-p"),
            "cut" to setOf("edit-cut"), "copy" to setOf("edit-copy"), "paste" to setOf("edit-paste"),
            "copyStyles" to setOf("edit-copy-styles"), "pasteStyles" to setOf("edit-paste-styles"),
            "selectAll" to setOf("edit-select-all"), "deleteSelectedElements" to setOf("edit-delete"),
            "duplicateSelection" to setOf("edit-duplicate"), "sendBackward" to setOf("edit-send-backward"),
            "bringForward" to setOf("edit-bring-forward"), "sendToBack" to setOf("edit-send-to-back"),
            "bringToFront" to setOf("edit-bring-to-front"), "copyAsPng" to setOf("edit-copy-png"),
            "group" to setOf("edit-group"), "ungroup" to setOf("edit-ungroup"), "gridMode" to setOf("edit-grid"),
            "zenMode" to setOf("edit-zen"), "objectsSnapMode" to setOf("edit-snap"), "stats" to setOf("edit-stats"),
            "flipHorizontal" to setOf("edit-flip-horizontal"), "flipVertical" to setOf("edit-flip-vertical"),
            "viewMode" to setOf("edit-view"), "hyperlink" to setOf("edit-hyperlink"),
            "toggleElementLock" to setOf("edit-lock"), "resetZoom" to setOf("reset-zoom-primary"),
            "zoomOut" to setOf("zoom-out-primary"), "zoomIn" to setOf("zoom-in-primary", "zoom-in-primary-shift"),
            "zoomToFitSelection" to setOf("fit-selection"), "zoomToFit" to setOf("fit"),
            "zoomToFitSelectionInViewport" to setOf("centre-selection"), "toggleEraserTool" to setOf("tool-eraser"),
            "toggleHandTool" to setOf("tool-hand"), "setFrameAsActiveTool" to setOf("tool-frame"),
            "saveFileToDisk" to setOf("edit-save"), "saveToActiveFile" to setOf("edit-save"),
            "toggleShortcuts" to setOf("edit-keys"), "searchMenu" to setOf("edit-find"),
        )
        val missing = buildList {
            for (convention in KeyConvention.entries) for (shortcut in shortcuts(convention)) for (keys in shortcut.keys) {
                val command = stroke(keys, convention)?.let { EditorCommands.find(it, convention) }
                if (command?.id?.value !in commands.getValue(shortcut.action)) add("$convention ${shortcut.action}: $keys")
            }
        }
        assertTrue(missing.isEmpty(), missing.joinToString("\n"))
    }
}
