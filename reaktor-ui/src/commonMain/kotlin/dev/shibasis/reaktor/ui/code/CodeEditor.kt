package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isShiftPressed as isPointerShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.shibasis.reaktor.code.CodeDiagnostic
import dev.shibasis.reaktor.code.CodeHover
import dev.shibasis.reaktor.code.CodeIntelligence
import dev.shibasis.reaktor.code.CodePosition
import dev.shibasis.reaktor.code.CodeSeverity
import dev.shibasis.reaktor.code.CodeSpan
import dev.shibasis.reaktor.ui.machinesignal.Eyebrow
import dev.shibasis.reaktor.ui.machinesignal.LocalMachineSignalFonts
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.SignalText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Character advance and line box in pixels. The plane is monospaced, so both are constants. */
data class CodeMetrics(val charWidth: Float, val lineHeight: Float)

object CodePalette {
    fun color(token: CodeToken): Color = with(MachineSignal.Editor.Code) {
        when (token) {
            CodeToken.Keyword -> Keyword
            CodeToken.Type -> Type
            CodeToken.Function -> Function
            CodeToken.Builtin -> Builtin
            CodeToken.Number -> Number
            CodeToken.Text -> Text
            CodeToken.Comment -> Comment
            CodeToken.Doc -> Doc
            CodeToken.Annotation -> Annotation
            CodeToken.Operator -> Operator
            CodeToken.Bracket -> Bracket
            CodeToken.Plain -> MachineSignal.Editor.Text
        }
    }

    fun color(severity: CodeSeverity): Color = when (severity) {
        CodeSeverity.Error -> MachineSignal.Status.Error
        CodeSeverity.Warning -> MachineSignal.Status.Warn
        else -> MachineSignal.Editor.Source
    }
}

@Composable
fun rememberCodeEditorState(
    text: String = "",
    language: CodeLanguage = CodeLanguage.Plain,
    uri: String = "reaktor://scratch",
    readOnly: Boolean = false,
): CodeEditorState = remember(uri) { CodeEditorState(text, language, uri, readOnly) }

/**
 * The code plane used everywhere in reaktor: a virtualised line list with its own caret, so a
 * 20k-line file scrolls at the cost of the viewport rather than the document. Deliberately a
 * scan-and-quick-edit surface — refactoring stays in the IDE that [onOpenExternally] jumps to.
 */
@Composable
fun CodeEditor(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    intelligence: CodeIntelligence = CodeIntelligence.None,
    showGutter: Boolean = true,
    showStatusBar: Boolean = true,
    fontSize: TextUnit = MachineSignal.Type.dataStrong,
    onRun: (() -> Unit)? = null,
    onOpenExternally: (() -> Unit)? = null,
    tag: String = "code-editor",
) {
    val fonts = LocalMachineSignalFonts.current
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()

    val style = remember(fontSize, fonts) {
        TextStyle(
            fontFamily = fonts.mono,
            fontSize = fontSize,
            lineHeight = fontSize * MachineSignal.Editor.Code.lineHeight,
            color = MachineSignal.Editor.Text,
        )
    }
    val metrics = remember(style, measurer, density) {
        val probe = measurer.measure(AnnotatedString("0".repeat(64)), style)
        CodeMetrics(probe.size.width / 64f, probe.size.height.toFloat())
    }
    val lineHeightDp = with(density) { metrics.lineHeight.toDp() }
    val gutterWidth = with(density) {
        if (!showGutter) 0.dp
        else (state.document.lineCount.toString().length * metrics.charWidth).toDp() +
            MachineSignal.Editor.Code.gutterPaddingX * 2
    }
    val contentWidth = remember(state.document, metrics) {
        with(density) { (state.document.longestLine * metrics.charWidth).toDp() + 48.dp }
    }

    var focused by remember { mutableStateOf(false) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var caretOn by remember { mutableStateOf(true) }
    var completionItems by remember { mutableStateOf<List<CodeCompletionItem>>(emptyList()) }
    var completionSelected by remember { mutableIntStateOf(0) }
    var completionTick by remember { mutableIntStateOf(0) }
    var hover by remember { mutableStateOf<CodeHover?>(null) }

    val prefix = state.document.prefixAt(state.caret)
    val completions = remember(completionItems, prefix) { filterCompletions(completionItems, prefix) }
    val occurrences = rememberOccurrences(state)

    fun closeCompletions() {
        completionItems = emptyList()
        completionSelected = 0
    }

    fun requestCompletions() {
        completionTick++
    }

    LaunchedEffect(intelligence, state.uri) {
        runCatching { intelligence.opened(state.source) }
    }
    DisposableEffect(intelligence, state.uri) {
        onDispose { scope.launch { runCatching { intelligence.closed(state.uri) } } }
    }
    LaunchedEffect(intelligence, state.uri, state.version) {
        if (state.version > 0) {
            delay(180)
            runCatching { intelligence.changed(state.source) }
        }
    }
    LaunchedEffect(intelligence, state.uri) {
        runCatching { intelligence.diagnostics(state.uri).collect { state.diagnostics = it } }
    }
    LaunchedEffect(completionTick) {
        if (completionTick == 0) return@LaunchedEffect
        val at = state.caret
        val local = documentCompletions(state)
        completionItems = local
        completionSelected = 0
        val remote = runCatching { intelligence.completions(state.uri, at) }.getOrDefault(emptyList())
        if (remote.isNotEmpty() && state.caret.line == at.line) {
            completionItems = mergeCompletions(remote, local)
            completionSelected = 0
        }
    }
    LaunchedEffect(intelligence, state.uri, state.caret, state.version) {
        hover = null
        if (!intelligence.status.available) return@LaunchedEffect
        delay(HoverDelayMillis)
        hover = runCatching { intelligence.hover(state.uri, state.caret) }.getOrNull()
    }
    LaunchedEffect(focused, state.caret) {
        caretOn = true
        if (!focused) return@LaunchedEffect
        while (true) {
            delay(CaretBlinkMillis)
            caretOn = !caretOn
        }
    }
    LaunchedEffect(state.caret, metrics, viewportWidth) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        val page = max(1, last - first)
        when {
            state.caret.line <= first -> listState.scrollToItem(max(0, state.caret.line - 1))
            state.caret.line >= last -> listState.scrollToItem(max(0, state.caret.line - page + 1))
        }
        if (viewportWidth > 0) {
            val x = (state.caret.column * metrics.charWidth).toInt()
            val margin = (metrics.charWidth * 6).toInt()
            when {
                x - margin < hScroll.value -> hScroll.scrollTo(max(0, x - margin))
                x + margin > hScroll.value + viewportWidth -> hScroll.scrollTo(x + margin - viewportWidth)
            }
        }
    }

    val onKey: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown) return@handler false
        val command = event.isCtrlPressed || event.isMetaPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        val pageLines = max(1, listState.layoutInfo.visibleItemsInfo.size - 1)

        if (completions.isNotEmpty()) when (event.key) {
            Key.DirectionDown -> {
                completionSelected = (completionSelected + 1) % completions.size
                return@handler true
            }
            Key.DirectionUp -> {
                completionSelected = (completionSelected + completions.size - 1) % completions.size
                return@handler true
            }
            Key.Enter, Key.Tab -> if (!command) {
                completions.getOrNull(completionSelected)?.let { state.applyCompletion(it.completion) }
                closeCompletions()
                return@handler true
            }
            Key.Escape -> {
                closeCompletions()
                return@handler true
            }
        }

        when (event.key) {
            Key.Escape -> {
                if (state.findVisible) state.closeFind() else state.clearSelection()
                return@handler true
            }
            Key.Enter, Key.NumPadEnter -> {
                if (command) {
                    onRun?.invoke()
                    return@handler onRun != null
                }
                return@handler state.newline()
            }
            Key.Tab -> return@handler state.indent(add = !shift)
            Key.Backspace -> {
                val handled = if (command || alt) state.deleteWord(forward = false) else state.backspace()
                if (completionItems.isNotEmpty()) requestCompletions()
                return@handler handled
            }
            Key.Delete -> return@handler if (alt) state.deleteWord(forward = true) else state.deleteForward()
            Key.DirectionLeft -> {
                closeCompletions()
                when {
                    command -> state.moveLineEdge(toStart = true, extend = shift)
                    alt -> state.moveWord(forward = false, extend = shift)
                    else -> state.moveHorizontal(forward = false, extend = shift)
                }
                return@handler true
            }
            Key.DirectionRight -> {
                closeCompletions()
                when {
                    command -> state.moveLineEdge(toStart = false, extend = shift)
                    alt -> state.moveWord(forward = true, extend = shift)
                    else -> state.moveHorizontal(forward = true, extend = shift)
                }
                return@handler true
            }
            Key.DirectionUp -> {
                if (alt) return@handler state.moveLines(up = true)
                closeCompletions()
                if (command) state.moveDocumentEdge(toStart = true, extend = shift) else state.moveVertical(-1, shift)
                return@handler true
            }
            Key.DirectionDown -> {
                if (alt) return@handler state.moveLines(up = false)
                closeCompletions()
                if (command) state.moveDocumentEdge(toStart = false, extend = shift) else state.moveVertical(1, shift)
                return@handler true
            }
            Key.MoveHome -> {
                if (command) state.moveDocumentEdge(toStart = true, extend = shift)
                else state.moveLineEdge(toStart = true, extend = shift)
                return@handler true
            }
            Key.MoveEnd -> {
                if (command) state.moveDocumentEdge(toStart = false, extend = shift)
                else state.moveLineEdge(toStart = false, extend = shift)
                return@handler true
            }
            Key.PageUp -> {
                state.moveVertical(-pageLines, shift)
                return@handler true
            }
            Key.PageDown -> {
                state.moveVertical(pageLines, shift)
                return@handler true
            }
        }

        if (command) when (event.key) {
            Key.A -> {
                state.selectAll()
                return@handler true
            }
            Key.C -> {
                state.selectedText?.let { clipboard.setText(AnnotatedString(it)) }
                return@handler true
            }
            Key.X -> {
                val cut = state.selectedText ?: state.document.line(state.caret.line)
                clipboard.setText(AnnotatedString(cut))
                return@handler state.selection?.let { state.edit(it, "") } ?: state.deleteLines()
            }
            Key.V -> {
                val pasted = clipboard.getText()?.text ?: return@handler true
                closeCompletions()
                return@handler state.edit(state.selection ?: CodeSpan.at(state.caret), pasted)
            }
            Key.Z -> return@handler if (shift) state.redo() else state.undo()
            Key.Y -> return@handler state.redo()
            Key.F -> {
                state.openFind(state.selectedText)
                return@handler true
            }
            Key.G -> {
                state.findNext(forward = !shift)
                return@handler true
            }
            Key.D -> return@handler state.duplicateLines()
            Key.K -> return@handler state.deleteLines()
            Key.Slash -> return@handler state.toggleComment()
            Key.Spacebar -> {
                requestCompletions()
                return@handler true
            }
            Key.B -> {
                onOpenExternally?.invoke()
                return@handler onOpenExternally != null
            }
            else -> return@handler false
        }

        val codePoint = event.utf16CodePoint
        if (!alt && codePoint >= 32 && codePoint != 127) {
            val typed = codePoint.toChar().toString()
            if (!state.type(typed)) return@handler false
            if (typed[0].isWordChar() || typed == ".") requestCompletions() else closeCompletions()
            return@handler true
        }
        false
    }

    Column(modifier.background(MachineSignal.Editor.Canvas).testTag(tag)) {
        if (state.findVisible) CodeFindBar(state, Modifier.fillMaxWidth(), tag)
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .focusable(interactionSource = remember { MutableInteractionSource() })
                .onPreviewKeyEvent(onKey)
                .semantics { contentDescription = "${state.language.label} editor, ${state.document.lineCount} lines" }
                .testTag("$tag-surface"),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(state.document.lineCount) { index ->
                    CodeLine(
                        index = index,
                        state = state,
                        metrics = metrics,
                        style = style,
                        lineHeight = lineHeightDp,
                        gutterWidth = gutterWidth,
                        contentWidth = contentWidth,
                        showGutter = showGutter,
                        occurrences = occurrences,
                        caretVisible = focused && caretOn,
                        hScroll = hScroll,
                        onViewportWidth = { viewportWidth = it },
                        onPress = { focus.requestFocus() },
                    )
                }
            }
            if (completions.isNotEmpty() && focused) {
                CodeCompletionPopup(
                    items = completions,
                    selected = completionSelected,
                    offset = completionOffset(state, listState, metrics, hScroll.value, gutterWidth, density),
                    onPick = { item -> state.applyCompletion(item.completion); closeCompletions() },
                    tag = tag,
                )
            }
            val problem = state.diagnostics.firstOrNull { state.caret in it.span.ordered }
            when {
                problem != null -> CodeTip(problem.message, problem.source, CodePalette.color(problem.severity), "$tag-diagnostic")
                hover != null -> CodeTip(hover!!.text, intelligence.status.name, MachineSignal.Editor.Muted, "$tag-hover")
            }
        }
        if (showStatusBar) CodeStatusBar(state, intelligence, onOpenExternally, tag)
    }
}

@Composable
private fun CodeLine(
    index: Int,
    state: CodeEditorState,
    metrics: CodeMetrics,
    style: TextStyle,
    lineHeight: Dp,
    gutterWidth: Dp,
    contentWidth: Dp,
    showGutter: Boolean,
    occurrences: List<CodeSpan>,
    caretVisible: Boolean,
    hScroll: androidx.compose.foundation.ScrollState,
    onViewportWidth: (Int) -> Unit,
    onPress: () -> Unit,
) {
    val text = state.document.line(index)
    val entry = state.entryState(index)
    val language = state.language
    val annotated = remember(text, entry, language) {
        AnnotatedString(
            text = text,
            spanStyles = language.spans(text, entry).map {
                AnnotatedString.Range(SpanStyle(color = CodePalette.color(it.token)), it.start, it.end)
            },
        )
    }
    val selection = state.selectionOn(index)
    val onCaretLine = state.caret.line == index
    val matches = state.findMatches.filter { it.start.line == index }
    val marks = occurrences.filter { it.start.line == index }
    val problems = state.diagnostics.filter { index >= it.span.ordered.start.line && index <= it.span.ordered.end.line }
    val clicks = remember { ClickTracker() }

    Row(Modifier.height(lineHeight).fillMaxWidth()) {
        if (showGutter) {
            Box(
                Modifier.width(gutterWidth).fillMaxHeight().background(MachineSignal.Editor.Canvas),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "${index + 1}",
                    style = style.copy(
                        color = if (onCaretLine) MachineSignal.Editor.Code.GutterActive else MachineSignal.Editor.Code.Gutter,
                        fontWeight = if (onCaretLine) FontWeight.Medium else FontWeight.Normal,
                    ),
                    maxLines = 1,
                    modifier = Modifier.padding(end = MachineSignal.Editor.Code.gutterPaddingX),
                )
                problems.minByOrNull { it.severity.ordinal }?.let { worst ->
                    Box(
                        Modifier.padding(start = 2.dp).width(3.dp).fillMaxHeight()
                            .background(CodePalette.color(worst.severity))
                            .align(Alignment.CenterStart),
                    )
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .onSizeChanged { onViewportWidth(it.width) }
                .horizontalScroll(hScroll),
        ) {
            Box(
                Modifier.width(contentWidth).fillMaxHeight()
                    .pointerHoverIcon(PointerIcon.Text)
                    .drawWithContent {
                        if (onCaretLine && selection == null) {
                            drawRect(MachineSignal.Editor.Code.CurrentLine, size = Size(size.width, size.height))
                        }
                        marks.forEach { fillColumns(it.start.column, it.end.column, metrics, MachineSignal.Editor.Code.Occurrence) }
                        matches.forEach { fillColumns(it.start.column, it.end.column, metrics, MachineSignal.Editor.Code.Match) }
                        selection?.let { fillColumns(it.first, it.last + 1, metrics, MachineSignal.Editor.Code.Selection) }
                        drawContent()
                        problems.forEach { problem ->
                            val span = problem.span.ordered
                            val from = if (span.start.line == index) span.start.column else 0
                            val to = if (span.end.line == index) span.end.column else text.length
                            squiggle(from, maxOf(to, from + 1), metrics, CodePalette.color(problem.severity))
                        }
                        if (caretVisible && onCaretLine) {
                            drawRect(
                                color = MachineSignal.Editor.Code.Caret,
                                topLeft = Offset(state.caret.column * metrics.charWidth, 1f),
                                size = Size(MachineSignal.Editor.Code.caretWidth.toPx(), size.height - 2f),
                            )
                        }
                    }
                    .pointerInput(state, metrics, index) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            onPress()
                            val extend = currentEvent.keyboardModifiers.isPointerShiftPressed
                            val at = positionOf(down.position, index, state, metrics)
                            when (clicks.register(at)) {
                                2 -> state.selectWordAt(at)
                                3 -> state.selectLines(at.line, at.line)
                                else -> state.moveTo(at, extend)
                            }
                            down.consume()
                            drag(down.id) { change ->
                                state.moveTo(positionOf(change.position, index, state, metrics), extend = true)
                                change.consume()
                            }
                        }
                    },
            ) {
                Text(text = annotated, style = style, softWrap = false, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CodeFindBar(state: CodeEditorState, modifier: Modifier, tag: String) = Row(
    modifier.height(MachineSignal.Editor.controlHeight + MachineSignal.Space.s2)
        .background(MachineSignal.Editor.Surface)
        .padding(horizontal = MachineSignal.Space.s3),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3),
) {
    val fonts = LocalMachineSignalFonts.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Eyebrow("FIND")
    BasicTextField(
        value = state.findQuery,
        onValueChange = { state.findQuery = it; state.findIndex = 0 },
        singleLine = true,
        modifier = Modifier.weight(1f).focusRequester(focus).testTag("$tag-find-query")
            .onPreviewKeyEvent {
                when {
                    it.type != KeyEventType.KeyDown -> false
                    it.key == Key.Enter || it.key == Key.NumPadEnter -> { state.findNext(forward = !it.isShiftPressed); true }
                    it.key == Key.Escape -> { state.closeFind(); true }
                    else -> false
                }
            },
        textStyle = TextStyle(
            color = MachineSignal.Editor.Text,
            fontFamily = fonts.mono,
            fontSize = MachineSignal.Type.dataStrong,
        ),
        cursorBrush = SolidColor(MachineSignal.Editor.Source),
    )
    val matches = state.findMatches
    SignalText(
        text = if (matches.isEmpty()) "no matches" else "${state.findIndex + 1} of ${matches.size}",
        color = if (matches.isEmpty()) MachineSignal.Text4 else MachineSignal.Editor.Muted,
        size = MachineSignal.Type.data,
        mono = true,
        modifier = Modifier.testTag("$tag-find-count"),
    )
    CodeChromeAction("Prev", "$tag-find-prev") { state.findNext(forward = false) }
    CodeChromeAction("Next", "$tag-find-next") { state.findNext(forward = true) }
    CodeChromeAction("Close", "$tag-find-close") { state.closeFind() }
}

@Composable
private fun CodeStatusBar(
    state: CodeEditorState,
    intelligence: CodeIntelligence,
    onOpenExternally: (() -> Unit)?,
    tag: String,
) = Row(
    Modifier.fillMaxWidth().height(MachineSignal.Editor.statusHeight)
        .background(MachineSignal.Editor.Surface)
        .padding(horizontal = MachineSignal.Space.s3)
        .testTag("$tag-status"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s3),
) {
    val errors = state.diagnostics.count { it.severity == CodeSeverity.Error }
    val warnings = state.diagnostics.count { it.severity == CodeSeverity.Warning }
    SignalText("Ln ${state.caret.line + 1}, Col ${state.caret.column + 1}", color = MachineSignal.Editor.Muted,
        size = MachineSignal.Type.data, mono = true, modifier = Modifier.testTag("$tag-caret"))
    state.selection?.let { SignalText("${state.selectedText?.length ?: 0} selected", color = MachineSignal.Text4, size = MachineSignal.Type.data, mono = true) }
    if (errors > 0) SignalText("$errors error${if (errors == 1) "" else "s"}", color = MachineSignal.Status.Error,
        size = MachineSignal.Type.data, mono = true, modifier = Modifier.testTag("$tag-errors"))
    if (warnings > 0) SignalText("$warnings warning${if (warnings == 1) "" else "s"}", color = MachineSignal.Status.Warn,
        size = MachineSignal.Type.data, mono = true)
    Spacer(Modifier.weight(1f))
    if (state.readOnly) SignalText("read only", color = MachineSignal.Text4, size = MachineSignal.Type.data, mono = true,
        modifier = Modifier.testTag("$tag-readonly"))
    SignalText(
        text = intelligence.status.name.lowercase(),
        color = if (intelligence.status.available) MachineSignal.Status.Ok else MachineSignal.Text4,
        size = MachineSignal.Type.data, mono = true,
        modifier = Modifier.testTag("$tag-intelligence"),
    )
    SignalText(state.language.label, color = MachineSignal.Editor.Muted, size = MachineSignal.Type.data, mono = true)
    onOpenExternally?.let { CodeChromeAction("Open in IDE", "$tag-open-ide", it) }
}

@Composable
internal fun CodeChromeAction(label: String, tag: String, onClick: () -> Unit) = SignalText(
    text = label,
    color = MachineSignal.Editor.Source,
    size = MachineSignal.Type.data,
    modifier = Modifier.clickable(onClick = onClick).testTag(tag)
        .padding(horizontal = MachineSignal.Space.s1),
)

@Composable
private fun CodeTip(message: String, origin: String?, accent: Color, tag: String) = Box(
    Modifier.fillMaxWidth().padding(MachineSignal.Space.s3),
    contentAlignment = Alignment.BottomStart,
) {
    Column(
        Modifier.background(MachineSignal.Editor.Raised, MachineSignal.Shape.Panel)
            .border(1.dp, accent, MachineSignal.Shape.Panel)
            .padding(MachineSignal.Space.s2)
            // One node, so the tip is announced as a sentence rather than two fragments.
            .semantics(mergeDescendants = true) {}
            .testTag(tag),
    ) {
        SignalText(message, color = MachineSignal.Editor.Text, size = MachineSignal.Type.data, maxLines = 6)
        origin?.let { SignalText(it, color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true) }
    }
}

/** Other appearances of the selected word, which is how a scan finds the rest of a symbol. */
@Composable
private fun rememberOccurrences(state: CodeEditorState): List<CodeSpan> {
    val selection = state.selection
    val word = selection?.takeIf { it.singleLine }?.let { state.document.slice(it) }
    return remember(state.document, word) {
        if (word == null || word.length < 2 || !word.all { it.isWordChar() }) emptyList()
        else state.document.matches(word, caseSensitive = true, wholeWord = true)
    }
}

private fun positionOf(offset: Offset, line: Int, state: CodeEditorState, metrics: CodeMetrics): CodePosition {
    val row = (line + floor(offset.y / metrics.lineHeight).toInt()).coerceIn(0, state.document.lastLine)
    val column = ((offset.x / metrics.charWidth) + 0.5f).toInt().coerceIn(0, state.document.line(row).length)
    return CodePosition(row, column)
}

private fun completionOffset(
    state: CodeEditorState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    metrics: CodeMetrics,
    scrollX: Int,
    gutterWidth: Dp,
    density: androidx.compose.ui.unit.Density,
): IntOffset {
    val row = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.caret.line }
    val y = (row?.offset ?: 0) + metrics.lineHeight
    val x = with(density) { gutterWidth.toPx() } + state.caret.column * metrics.charWidth - scrollX
    return IntOffset(max(0f, x).toInt(), y.toInt())
}

private class ClickTracker {
    private var last: CodePosition? = null
    private var mark: TimeMark? = null
    private var count = 0

    fun register(at: CodePosition): Int {
        val previous = last
        val quick = mark?.elapsedNow()?.let { it < DoubleClickWindow } == true
        count = if (quick && previous != null && previous.line == at.line && abs(previous.column - at.column) <= 1) count + 1 else 1
        last = at
        mark = TimeSource.Monotonic.markNow()
        return count
    }

    companion object {
        private val DoubleClickWindow = 400.milliseconds
    }
}

private const val CaretBlinkMillis = 530L
private const val HoverDelayMillis = 320L

internal fun DrawScope.fillColumns(from: Int, to: Int, metrics: CodeMetrics, color: Color) {
    if (to <= from) return
    drawRect(
        color = color,
        topLeft = Offset(from * metrics.charWidth, 0f),
        size = Size((to - from) * metrics.charWidth, size.height),
    )
}

internal fun DrawScope.squiggle(from: Int, to: Int, metrics: CodeMetrics, color: Color) {
    val baseline = size.height - 1.5f
    val step = metrics.charWidth / 2f
    val end = to * metrics.charWidth
    var x = from * metrics.charWidth
    var up = true
    while (x < end && step > 0f) {
        val next = minOf(x + step, end)
        drawLine(
            color = color,
            start = Offset(x, if (up) baseline else baseline - 2.5f),
            end = Offset(next, if (up) baseline - 2.5f else baseline),
            strokeWidth = 1f,
        )
        x = next
        up = !up
    }
}
