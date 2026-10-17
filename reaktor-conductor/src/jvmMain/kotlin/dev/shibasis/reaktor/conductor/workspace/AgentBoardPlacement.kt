package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.HarnessActivity
import dev.shibasis.reaktor.conductor.cli.HarnessSession
import dev.shibasis.reaktor.tooling.query.SearchQuery

val HarnessSession.boardRef: String get() = (if (runtime == RuntimeKind.Codex) BoardRefs.Codex else BoardRefs.Claude) + id

fun HarnessSession.boardWork(): BoardWork = when (activity) {
    HarnessActivity.NeedsYou -> BoardWork(boardRef, BoardWorkState.Asking, lastActiveAt)
    HarnessActivity.Working -> BoardWork(boardRef, BoardWorkState.Executing, startedAt ?: lastActiveAt)
    HarnessActivity.Waiting, HarnessActivity.Ended -> BoardWork(boardRef, BoardWorkState.Stopped, lastActiveAt)
}

fun BoardPage.placed(sessions: List<HarnessSession>): BoardPage {
    val byRef = sessions.associateBy { it.boardRef }
    val tasks = entries.associate { it.task.id to it.task }
    return copy(entries = entries.map { entry ->
        val outside = entry.task.work.mapNotNull { ref -> byRef[ref]?.boardWork() }
        if (outside.isEmpty()) entry else entry.task.entry(entry.work + outside, tasks)
    })
}

fun BoardPage.matching(query: String): BoardPage {
    require(query.length <= 200) { "A board query has at most 200 characters" }
    val search = SearchQuery(query)
    return copy(entries = entries.filter { entry ->
        search.matches("${entry.task.id} ${entry.task.title} ${entry.column.label} ${entry.task.intent}") { key, value, pattern ->
            val field = when (key) { "id" -> entry.task.id; "title" -> entry.task.title; "column" -> entry.column.name; "stage" -> entry.task.stage.name; else -> null }
            field?.let { pattern?.containsMatchIn(it) ?: it.contains(value, ignoreCase = true) }
        }
    })
}
