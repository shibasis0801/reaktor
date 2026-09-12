package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.shibasis.reaktor.code.CodeCompletion
import dev.shibasis.reaktor.code.CodeCompletionKind
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.SignalText

/** [fromServer] survives the merge so server-ranked items keep their place above the buffer's own words. */
data class CodeCompletionItem(val completion: CodeCompletion, val fromServer: Boolean)

private const val MaxCompletions = 40

/**
 * The editor's own vocabulary: every identifier already in the buffer plus the language's words.
 * This is what makes the surface useful with no server attached, which is the common case on the
 * non-JVM targets.
 */
internal fun documentCompletions(state: CodeEditorState): List<CodeCompletionItem> {
    val language = state.language
    val typing = state.document.prefixAt(state.caret)
    val identifiers = state.document.identifiers(within = state.completionScanRange())
        .filter { it != typing }
        .map { CodeCompletion(it, kind = CodeCompletionKind.Text, detail = "in file") }
    val keywords = language.keywords.map { CodeCompletion(it, kind = CodeCompletionKind.Keyword, detail = language.label) }
    val types = language.types.map { CodeCompletion(it, kind = CodeCompletionKind.Class, detail = language.label) }
    val builtins = language.builtins.map { CodeCompletion(it, kind = CodeCompletionKind.Function, detail = language.label) }
    return (identifiers + keywords + types + builtins).map { CodeCompletionItem(it, fromServer = false) }
}

internal fun mergeCompletions(remote: List<CodeCompletion>, local: List<CodeCompletionItem>): List<CodeCompletionItem> {
    val served = remote.mapTo(HashSet()) { it.label }
    return remote.map { CodeCompletionItem(it, fromServer = true) } +
        local.filter { it.completion.label !in served }
}

internal fun filterCompletions(items: List<CodeCompletionItem>, prefix: String): List<CodeCompletionItem> {
    if (items.isEmpty()) return emptyList()
    val matched = if (prefix.isEmpty()) items
    else items.filter { it.completion.label.startsWith(prefix, ignoreCase = true) }
    if (prefix.isNotEmpty() && matched.size == 1 && matched.single().completion.label == prefix) return emptyList()
    return matched
        .distinctBy { it.completion.label }
        .sortedWith(
            compareByDescending<CodeCompletionItem> { it.fromServer }
                .thenByDescending { it.completion.label.startsWith(prefix) }
                .thenBy { it.completion.order },
        )
        .take(MaxCompletions)
}

@Composable
internal fun CodeCompletionPopup(
    items: List<CodeCompletionItem>,
    selected: Int,
    offset: IntOffset,
    onPick: (CodeCompletionItem) -> Unit,
    tag: String,
) = Popup(alignment = Alignment.TopStart, offset = offset) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected, items.size) {
        if (selected in items.indices) listState.scrollToItem(selected)
    }
    Column(
        Modifier.width(CompletionWidth).heightIn(max = CompletionMaxHeight)
            .background(MachineSignal.Editor.Raised, MachineSignal.Shape.Panel)
            .border(1.dp, MachineSignal.Editor.Line, MachineSignal.Shape.Panel)
            .testTag("$tag-completions"),
    ) {
        LazyColumn(state = listState) {
            itemsIndexed(items) { index, item ->
                CodeCompletionRow(item, index == selected, "$tag-completion-$index") { onPick(item) }
            }
        }
    }
}

@Composable
private fun CodeCompletionRow(item: CodeCompletionItem, active: Boolean, tag: String, onPick: () -> Unit) = Row(
    Modifier.fillMaxWidth().height(MachineSignal.Metrics.kvRowHeight)
        .background(if (active) MachineSignal.Editor.AccentSoft else MachineSignal.Editor.Raised)
        .clickable(onClick = onPick)
        .padding(horizontal = MachineSignal.Space.s2)
        .testTag(tag),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s2),
) {
    Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
        SignalText(
            text = item.completion.kind.sigil,
            color = if (item.fromServer) MachineSignal.Editor.Source else MachineSignal.Text4,
            size = MachineSignal.Type.dataMicro,
            mono = true,
        )
    }
    SignalText(
        text = item.completion.label,
        color = if (active) MachineSignal.Editor.Text else MachineSignal.Editor.Muted,
        size = MachineSignal.Type.dataStrong,
        mono = true,
    )
    Spacer(Modifier.weight(1f))
    item.completion.detail?.let {
        SignalText(it, color = MachineSignal.Text4, size = MachineSignal.Type.dataMicro, mono = true)
    }
}

private val CompletionWidth = 380.dp
private val CompletionMaxHeight = 220.dp
