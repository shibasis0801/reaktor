package dev.shibasis.reaktor.cli

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.time.Duration.Companion.days
import kotlin.time.TimeSource

data class PickerOption(
    val value: String,
    val label: String = value,
    val detail: String = "",
    val keywords: List<String> = emptyList(),
)

fun selectOption(
    env: ReaktorEnv,
    title: String,
    options: List<PickerOption>,
    emptyMessage: String,
): String {
    if (options.isEmpty()) throw CliktError(emptyMessage)

    val terminal = env.terminal
    if (!terminal.terminalInfo.inputInteractive) {
        renderStaticOptions(env, title, options)
        throw CliktError("missing selection. Pass one of the options above.")
    }

    selectWithFzf(title, options)?.let { return it }

    return try {
        selectWithBuiltInFinder(terminal, title, options)
    } catch (error: Abort) {
        throw error
    } catch (error: RuntimeException) {
        renderStaticOptions(env, title, options)
        throw CliktError("interactive selection is not available in this terminal. Pass one of the options above.")
    }
}

fun renderStaticOptions(env: ReaktorEnv, title: String, options: List<PickerOption>) {
    val width = env.terminal.size.width
    val labelWidth = options.maxOf { it.label.length }.coerceIn(12, 30)
    env.terminal.println("")
    env.terminal.println("  " + bold(title))
    options.forEachIndexed { index, option ->
        env.terminal.println(
            "    " +
                dim("${index + 1}.".padStart(3)) + " " +
                bold(option.label.padEnd(labelWidth)) + " " +
                dim(option.detail.compact(width - labelWidth - 10)),
        )
    }
}

private fun selectWithFzf(title: String, options: List<PickerOption>): String? {
    val fzf = findExecutable("fzf") ?: return null
    val process = ProcessBuilder(
        fzf.absolutePath,
        "--height=~40%",
        "--layout=reverse",
        "--border",
        "--cycle",
        "--no-multi",
        "--prompt=$title> ",
        "--delimiter=\t",
        "--with-nth=2,3",
        "--nth=2,3,4",
        "--accept-nth=1",
    )
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    process.outputStream.bufferedWriter().use { writer ->
        options.forEach { option ->
            writer.append(option.value.sanitizeField())
                .append('\t')
                .append(option.label.sanitizeField())
                .append('\t')
                .append(option.detail.sanitizeField())
                .append('\t')
                .append(option.keywords.joinToString(" ").sanitizeField())
                .append('\n')
        }
    }

    val output = process.inputStream.bufferedReader().readText().trim()
    val code = process.waitFor()
    if (code == 0 && output.isNotBlank()) return output
    if (code == 1 || code == 130) throw Abort()
    return null
}

private fun selectWithBuiltInFinder(terminal: Terminal, title: String, options: List<PickerOption>): String {
    val state = PickerState(options)
    var renderedLines = 0

    fun render() {
        clearRenderedBlock(terminal, renderedLines)
        val lines = state.renderFinder(title, terminal.size.width, terminal.size.height)
        lines.forEach { terminal.println(it) }
        renderedLines = lines.size
    }

    terminal.cursor.hide(false)
    val rawMode = terminal.terminalInterface.enterRawMode(MouseTracking.Off)
    try {
        render()
        var selected: String? = null
        while (selected == null) {
            val event = terminal.terminalInterface.readInputEvent(
                TimeSource.Monotonic.markNow() + 365.days,
                MouseTracking.Off,
            )
            if (event !is KeyboardEvent) continue
            when (val result = state.handleKey(event)) {
                PickerResult.Continue -> {
                    render()
                    continue
                }
                PickerResult.Cancel -> throw Abort()
                is PickerResult.Done -> selected = result.value
            }
        }
        return selected
    } finally {
        rawMode.close()
        clearRenderedBlock(terminal, renderedLines)
        terminal.cursor.show()
    }
}

private fun clearRenderedBlock(terminal: Terminal, lines: Int) {
    if (lines <= 0) return
    val clear = buildString {
        repeat(lines) {
            append("\u001B[1A")
            append("\r")
            append("\u001B[2K")
        }
    }
    terminal.rawPrint(clear, stderr = false)
}

private fun findExecutable(name: String): File? =
    System.getenv("PATH")
        .orEmpty()
        .split(File.pathSeparator)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }

private fun String.sanitizeField(): String =
    replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

internal sealed interface PickerResult {
    data object Continue : PickerResult
    data object Cancel : PickerResult
    data class Done(val value: String) : PickerResult
}

internal class PickerState(private val options: List<PickerOption>) {
    private var query = ""
    private var cursorIndex = 0

    internal val currentQuery: String get() = query
    internal val currentCursorIndex: Int get() = cursorIndex

    fun handleKey(event: KeyboardEvent): PickerResult {
        return when {
            event.isCancel() -> PickerResult.Cancel
            event.key == "Enter" -> visibleOptions().getOrNull(cursorIndex)?.let { PickerResult.Done(it.value) }
                ?: PickerResult.Continue
            event.isNext() -> {
                moveCursor(1)
                PickerResult.Continue
            }
            event.isPrevious() -> {
                moveCursor(-1)
                PickerResult.Continue
            }
            event.key == "PageDown" -> {
                moveCursor(10)
                PickerResult.Continue
            }
            event.key == "PageUp" -> {
                moveCursor(-10)
                PickerResult.Continue
            }
            event.key == "Home" || (event.ctrl && event.key.equals("a", ignoreCase = true)) -> {
                cursorIndex = 0
                PickerResult.Continue
            }
            event.key == "End" || (event.ctrl && event.key.equals("e", ignoreCase = true)) -> {
                cursorIndex = visibleOptions().lastIndex.coerceAtLeast(0)
                PickerResult.Continue
            }
            event.key == "Backspace" || event.key == "Delete" -> {
                if (query.isNotEmpty()) {
                    query = query.dropLast(1)
                    cursorIndex = 0
                }
                PickerResult.Continue
            }
            event.ctrl && event.key.equals("u", ignoreCase = true) -> {
                query = ""
                cursorIndex = 0
                PickerResult.Continue
            }
            event.ctrl && event.key.equals("w", ignoreCase = true) -> {
                query = query.trimEnd().substringBeforeLast(' ', "")
                cursorIndex = 0
                PickerResult.Continue
            }
            event.isPrintable() -> {
                query += event.key
                cursorIndex = 0
                PickerResult.Continue
            }
            else -> PickerResult.Continue
        }
    }

    fun submit(input: String): PickerResult {
        input.forEach { char -> handleKey(KeyboardEvent(char.toString())) }
        return handleKey(KeyboardEvent("Enter"))
    }

    fun renderFinder(title: String, terminalWidth: Int, terminalHeight: Int): List<String> {
        val visible = visibleOptions()
        val width = max(terminalWidth, 80)
        val labelWidth = options.maxOf { it.label.length }.coerceIn(12, 30)
        val maxRows = (max(terminalHeight, 8) - 5).coerceIn(4, 14)
        val windowStart = cursorWindowStart(visible.size, maxRows)
        val window = visible.drop(windowStart).take(maxRows)
        val detailWidth = (width - labelWidth - 12).coerceAtLeast(18)
        val rows = mutableListOf<String>()
        val position = if (visible.isEmpty()) 0 else cursorIndex + 1

        rows += "  " + (bold + cyan)(title) + dim("  type to filter · arrows/ctrl-jk move · enter selects · esc cancels")
        rows += "  " + green("> ") + (if (query.isBlank()) dim("filter") else query) +
            dim("  $position/${visible.size} of ${options.size}")
        if (visible.isEmpty()) {
            rows += "    " + dim("no matches")
            return rows
        }
        if (windowStart > 0) rows += "    " + dim("... ${windowStart} above")
        window.forEachIndexed { offset, option ->
            val index = windowStart + offset
            val active = index == cursorIndex
            val marker = if (active) green(">") else dim(" ")
            val label = if (active) bold(option.label.padEnd(labelWidth)) else option.label.padEnd(labelWidth)
            rows += "  $marker $label " + dim(option.detail.compact(detailWidth))
        }
        val remaining = visible.size - windowStart - window.size
        if (remaining > 0) rows += "    " + dim("... $remaining below")
        return rows
    }

    internal fun visibleOptions(): List<PickerOption> {
        val q = query.trim()
        val scored = if (q.isBlank()) {
            options.mapIndexed { index, option -> ScoredOption(option, -index) }
        } else {
            options.mapNotNull { option ->
                fuzzyScore(q, option)?.let { ScoredOption(option, it) }
            }.sortedWith(compareByDescending<ScoredOption> { it.score }.thenBy { it.option.label.lowercase(Locale.ROOT) })
        }
        return scored.map { it.option }
    }

    private fun cursorWindowStart(size: Int, maxRows: Int): Int {
        if (size <= maxRows) return 0
        val half = maxRows / 2
        return (cursorIndex - half).coerceIn(0, size - maxRows)
    }

    private fun moveCursor(delta: Int) {
        val size = visibleOptions().size
        if (size == 0) {
            cursorIndex = 0
            return
        }
        cursorIndex = (cursorIndex + delta).floorMod(size)
    }
}

private fun KeyboardEvent.isCancel(): Boolean =
    key == "Escape" ||
        (ctrl && key.equals("c", ignoreCase = true)) ||
        (ctrl && key.equals("g", ignoreCase = true))

private fun KeyboardEvent.isNext(): Boolean =
    key == "ArrowDown" || (ctrl && (key.equals("j", ignoreCase = true) || key.equals("n", ignoreCase = true)))

private fun KeyboardEvent.isPrevious(): Boolean =
    key == "ArrowUp" || (ctrl && (key.equals("k", ignoreCase = true) || key.equals("p", ignoreCase = true)))

private fun KeyboardEvent.isPrintable(): Boolean =
    !ctrl && !alt && key.length == 1 && key[0].code >= 32

private fun Int.floorMod(size: Int): Int =
    ((this % size) + size) % size

private data class ScoredOption(val option: PickerOption, val score: Int)

private fun fuzzyScore(query: String, option: PickerOption): Int? {
    val tokens = query.lowercase(Locale.ROOT)
        .split(Regex("""\s+"""))
        .filter { it.isNotBlank() }
    val haystacks = listOf(option.label, option.value, option.detail) + option.keywords
    var total = 0
    for (token in tokens) {
        val best = haystacks.maxOfOrNull { scoreToken(token, it) } ?: -1
        if (best < 0) return null
        total += best
    }
    return total
}

private fun scoreToken(token: String, value: String): Int {
    val text = value.lowercase(Locale.ROOT)
    val compactText = text.filter { it.isLetterOrDigit() }
    val compactToken = token.filter { it.isLetterOrDigit() }
    if (compactToken.isEmpty()) return -1
    if (text == token || compactText == compactToken) return 1_000
    if (text.startsWith(token) || compactText.startsWith(compactToken)) return 900 - text.length.coerceAtMost(100)
    val contains = text.indexOf(token).takeIf { it >= 0 } ?: compactText.indexOf(compactToken)
    if (contains >= 0) return 760 - contains.coerceAtMost(100)
    val subsequence = subsequenceScore(compactToken, compactText)
    if (subsequence >= 0) return subsequence
    val edit = editDistance(compactToken, compactText.take(compactToken.length + 2))
    return if (edit <= 2) 350 - edit * 40 else -1
}

private fun subsequenceScore(needle: String, haystack: String): Int {
    var pos = 0
    var gaps = 0
    for (char in needle) {
        val next = haystack.indexOf(char, pos)
        if (next < 0) return -1
        gaps += next - pos
        pos = next + 1
    }
    return 600 - gaps.coerceAtMost(200)
}

private fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val previous = IntArray(b.length + 1) { it }
    val current = IntArray(b.length + 1)
    for (i in a.indices) {
        current[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            current[j + 1] = minOf(
                current[j] + 1,
                previous[j + 1] + 1,
                previous[j] + cost,
            )
        }
        for (j in previous.indices) previous[j] = current[j]
    }
    return previous[b.length]
}

private fun String.compact(width: Int): String {
    if (width <= 4) return take(width.coerceAtLeast(0))
    val clean = replace(Regex("""\s+"""), " ")
    return if (clean.length <= width) clean else clean.take(width - 1) + "..."
}
