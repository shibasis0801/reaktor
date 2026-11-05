@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalDensity
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalSurface
import dev.shibasis.reaktor.ui.machinesignal.MachineSignalVariant
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeEditorAppearanceTest {
    @Test
    fun capturesDefaultEditorsAndANarrowSourcePane() {
        val kotlin = CodeEditorState(
            """
            data class Session(val roles: Set<String>)
            fun canEdit(session: Session): Boolean {
                // Keep policy checks close to the action.
                return "editor" in session.roles
            }
            """.trimIndent(),
            CodeLanguage.Kotlin,
        ).apply {
            moveTo(CodePosition(3, 11))
            moveTo(CodePosition(3, 19), extend = true)
        }
        val cpp = CodeEditorState(
            """
            #include <string_view>
            struct Document { std::string_view owner; };
            bool can_edit(const Document& doc, std::string_view user) {
                return doc.owner == user; // ownership grants access
            }
            """.trimIndent(),
            CodeLanguage.Cpp,
        )
        val openFga = CodeEditorState(
            """
            model
              schema 1.1
            type user
            type document
              relations
                define owner: [user]
                define editor: [user] or owner
            """.trimIndent(),
            CodeLanguage.OpenFga,
        )
        assertEquals("\"editor\"", kotlin.selectedText)

        SwingUtilities.invokeAndWait {
            val wideFocus = FocusRequester()
            capture("default-languages.png", 900, 600) {
                Column(
                    Modifier.fillMaxSize().background(MachineSignal.Editor.Canvas).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CodeEditor(kotlin, Modifier.fillMaxWidth().weight(1f), focusRequester = wideFocus)
                    CodeEditor(cpp, Modifier.fillMaxWidth().weight(1f))
                    CodeEditor(openFga, Modifier.fillMaxWidth().weight(1f))
                }
                LaunchedEffect(Unit) { wideFocus.requestFocus() }
            }
            val narrowFocus = FocusRequester()
            capture("narrow-source-pane.png", 360, 600) {
                CodeEditor(kotlin, Modifier.fillMaxSize(), focusRequester = narrowFocus)
                LaunchedEffect(Unit) { narrowFocus.requestFocus() }
            }
        }
        assertEquals("\"editor\"", kotlin.selectedText)
    }

    private fun capture(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        val output = Path.of("build/reports/native-editor", name)
        Files.createDirectories(output.parent)
        val scene = ImageComposeScene(width, height) {
            MachineSignalSurface(MachineSignalVariant.Editor, MachineSignalDensity.Compact, content = content)
        }
        try {
            repeat(3) { frame ->
                Snapshot.sendApplyNotifications()
                scene.render(frame * 16_666_667L).close()
            }
            val image = scene.render(50_000_001L)
            try {
                val png = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
                try {
                    Files.write(output, png.bytes)
                } finally {
                    png.close()
                }
            } finally {
                image.close()
            }
        } finally {
            scene.close()
        }
    }
}
