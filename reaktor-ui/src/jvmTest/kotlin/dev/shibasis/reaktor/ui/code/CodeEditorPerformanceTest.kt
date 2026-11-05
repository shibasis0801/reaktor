@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSpan
import dev.shibasis.reaktor.performance.ReaktorPerformanceReport
import dev.shibasis.reaktor.performance.ReaktorPerformanceSample
import dev.shibasis.reaktor.performance.ReaktorProfileCapture
import dev.shibasis.reaktor.performance.ReaktorProfilerKind
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class CodeEditorPerformanceTest {
    @Test fun nativeEditingAndStreamingStayWithinTheirCpuBudget() {
        val output = Path.of("build/reports/native-editor")
        Files.createDirectories(output)
        val started = Instant.now()
        val start = System.nanoTime()
        val samples = mutableListOf<ReaktorPerformanceSample>()
        Recording(Configuration.getConfiguration("profile")).use { recording ->
            recording.start()
            for (size in listOf(2_000, 20_000)) {
                val state = CodeEditorState((0 until size).joinToString("\n") { "val line$it = $it" }, CodeLanguage.Kotlin)
                state.entryState(size - 1)
                samples += measure("kotlin-$size-edit-and-visible-syntax") {
                    state.edit(CodeSpan.of(size / 2, 0, 1), "v")
                    for (line in size / 2 until size / 2 + 30) state.spans(line)
                    state.document.longestLine
                }
            }
            for ((language, source) in listOf(
                CodeLanguage.Cpp to "constexpr auto text = R\"tag(// literal)tag\"; // note",
                CodeLanguage.OpenFga to "define viewer: [user:*, group#member] or editor from parent # note",
            )) {
                val state = CodeEditorState(List(20_000) { source }.joinToString("\n"), language)
                state.entryState(19_999)
                samples += measure("${language.id}-20k-edit-and-visible-syntax") {
                    state.edit(CodeSpan.of(10_000, 0, 1), source.take(1))
                    for (line in 10_000 until 10_030) state.spans(line)
                }
            }
            val markdown = CodeEditorState((0 until 2_000).joinToString("\n\n") { "Paragraph $it with **evidence**." } + "\n\nStreaming", CodeLanguage.Markdown, readOnly = true)
            markdown.markdown.blocks(markdown.document)
            samples += measure("markdown-2000-blocks-stream-and-project") {
                markdown.append(" x")
                markdown.markdown.blocks(markdown.document)
            }
            val wrapped = CodeEditorState("x".repeat(100_000), CodeLanguage.Markdown)
            samples += measure("markdown-100k-wrap-and-visible-syntax") {
                val rows = CodeRows(wrapped.document, 80)
                repeat(30) { wrapped.spans(rows[it].line) }
            }
            val controlled = CodeEditorState("x".repeat(100_000), CodeLanguage.Markdown, onChange = { assertTrue(it.length >= 100_000) })
            controlled.moveTo(CodePosition(0, 100_000))
            samples += measure("agent-100k-controlled-input") { controlled.type("x") }
            SwingUtilities.invokeAndWait {
                for ((name, state) in listOf(
                    "kotlin-20k-edit-and-render" to CodeEditorState((0 until 20_000).joinToString("\n") { "val line$it = $it" }, CodeLanguage.Kotlin),
                    "markdown-100k-edit-and-render" to CodeEditorState("text ".repeat(20_000), CodeLanguage.Markdown),
                )) {
                    val scene = ImageComposeScene(800, 600) { CodeEditor(state, Modifier.fillMaxSize()) }
                    try {
                        var frame = 0L
                        samples += measure(name) {
                            state.type("x")
                            Snapshot.sendApplyNotifications()
                            scene.render(++frame * 16_666_667L).close()
                        }
                    } finally { scene.close() }
                }
            }
            recording.stop()
            recording.dump(output.resolve("editor.jfr"))
        }
        val report = ReaktorPerformanceReport("native-editor-jvm", started.toString(), samples = samples,
            profiles = listOf(ReaktorProfileCapture("native-editor-cpu", ReaktorProfilerKind.Jfr,
                System.getProperty("os.name"), started.toString(), (System.nanoTime() - start) / 1_000_000.0,
                output.resolve("editor.jfr").toAbsolutePath().toString())))
        Files.writeString(output.resolve("editor.json"), Json { prettyPrint = true }.encodeToString(ReaktorPerformanceReport.serializer(), report))
        samples.forEach { assertTrue(it.medianMs < 16.0, "${it.name}: ${it.medianMs} ms median exceeds 16 ms CPU budget") }
    }

    private fun measure(name: String, action: () -> Unit): ReaktorPerformanceSample {
        repeat(40) { action() }
        val times = List(200) {
            val start = System.nanoTime()
            action()
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        return ReaktorPerformanceSample(name, times.size, times[times.size / 2], times.first(), times.last(), budgetMs = 16.0)
    }
}
