package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

class AgentBoardStore(private val directory: Path, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable
    private data class Ledger(val revision: Long = 0, val next: Int = 1)

    private val ledgerFile = directory.resolve("board.json")
    private val tasks = mutableMapOf<String, BoardTask>()
    private var ledger: Ledger
    private val _changes: MutableStateFlow<Long>
    val changes: StateFlow<Long> get() = _changes.asStateFlow()
    val revision: Long get() = _changes.value

    init {
        privateDirectory(directory.resolve("tasks"))
        Files.list(directory.resolve("tasks")).use { files ->
            files.filter { it.fileName.toString().matches(TaskFile) }.forEach { path ->
                ConductorJson.decodeFromString(BoardTask.serializer(), Files.readString(path)).let { tasks[it.id] = it }
            }
        }
        val saved = if (Files.exists(ledgerFile)) ConductorJson.decodeFromString(Ledger.serializer(), Files.readString(ledgerFile)) else Ledger()
        ledger = saved.copy(next = maxOf(saved.next, (tasks.values.maxOfOrNull { it.number } ?: 0) + 1))
        _changes = MutableStateFlow(ledger.revision)
    }

    @Synchronized fun tasks(): List<BoardTask> = tasks.values.sortedByDescending { it.number }

    @Synchronized fun get(id: String): BoardTask = tasks[id] ?: error("$id is not on the board")

    @Synchronized fun create(draft: BoardDraft, by: BoardActor): BoardTask {
        require(draft.stage != BoardStage.Done) { "A new task cannot start in Done" }
        val now = clock()
        val task = BoardTask(
            id = "T${ledger.next}",
            title = draft.title.title(),
            intent = draft.intent.bounded("intent", 20_000),
            acceptance = draft.acceptance.lines("acceptance", 30, 500),
            scope = draft.scope.lines("scope", 100, 500),
            stage = draft.stage,
            work = draft.work.refs(),
            links = draft.links.checked(),
            parent = draft.parent?.let { get(it).id },
            createdBy = by.checked(),
            createdAt = now,
            updatedAt = now,
            stageChangedAt = now,
            history = listOf(BoardEvent(now, by, "Created in ${draft.stage.column.label}")),
        )
        return save(task, ledger.next + 1)
    }

    @Synchronized fun update(id: String, patch: BoardPatch, by: BoardActor, expected: Long? = null): BoardTask {
        val task = current(id, expected)
        require(!(patch.unblock && patch.blocker != null)) { "Set a blocker or clear it, not both" }
        val now = clock()
        val blocker = when {
            patch.unblock -> null
            patch.blocker != null -> patch.blocker.checked(id)
            else -> task.blocker
        }
        val next = task.copy(
            title = patch.title?.title() ?: task.title,
            intent = patch.intent?.bounded("intent", 20_000) ?: task.intent,
            acceptance = patch.acceptance?.lines("acceptance", 30, 500) ?: task.acceptance,
            scope = patch.scope?.lines("scope", 100, 500) ?: task.scope,
            links = patch.links?.checked() ?: task.links,
            blocker = blocker,
            blockedAt = if (blocker == task.blocker) task.blockedAt else blocker?.let { now },
        )
        val edited = listOfNotNull(
            "title".takeIf { next.title != task.title },
            "intent".takeIf { next.intent != task.intent },
            "acceptance".takeIf { next.acceptance != task.acceptance },
            "scope".takeIf { next.scope != task.scope },
            "links".takeIf { next.links != task.links },
        )
        val changes = listOfNotNull(
            edited.takeIf { it.isNotEmpty() }?.let { "Edited ${it.joinToString(", ")}" },
            when {
                blocker == task.blocker -> null
                blocker == null -> "Unblocked"
                else -> "Blocked: ${next.blockedBy(tasks).orEmpty()}"
            },
        )
        return if (changes.isEmpty()) task else save(next.changed(now, by.checked(), changes.joinToString("; ")))
    }

    @Synchronized fun move(id: String, stage: BoardStage, by: BoardActor, expected: Long? = null): BoardTask {
        val task = current(id, expected)
        if (by.checked().kind != BoardActorKind.Person) {
            require(stage != BoardStage.Done) { "Only a person moves a task to Done. Move ${task.id} to Review and a person lands it." }
            require(task.stage != BoardStage.Done) { "${task.id} is done; only a person reopens it." }
        }
        val now = clock()
        val change = if (stage == task.stage) "Kept in ${stage.column.label}" else "Moved to ${stage.column.label}"
        return save(task.copy(stage = stage, stageChangedAt = now).changed(now, by, change))
    }

    @Synchronized fun link(id: String, ref: String, linked: Boolean, by: BoardActor): BoardTask {
        val task = get(id)
        val work = if (linked) (task.work + listOf(ref).refs()).distinct() else task.work - ref
        require(work.size <= 50) { "A task links at most 50 runs and sessions" }
        if (work == task.work) return task
        return save(task.copy(work = work).changed(clock(), by.checked(), if (linked) "Linked $ref" else "Unlinked $ref"))
    }

    private fun current(id: String, expected: Long?): BoardTask = get(id).also { task ->
        require(expected == null || task.revision == expected) { "${task.id} changed since revision $expected; read it again" }
    }

    private fun save(task: BoardTask, next: Int = ledger.next): BoardTask {
        atomicWrite(directory.resolve("tasks/${task.id}.json"), ConductorJson.encodeToString(BoardTask.serializer(), task))
        tasks[task.id] = task
        ledger = Ledger(ledger.revision + 1, next)
        atomicWrite(ledgerFile, ConductorJson.encodeToString(Ledger.serializer(), ledger))
        _changes.value = ledger.revision
        return task
    }

    private fun BoardTask.changed(now: Long, by: BoardActor, change: String) =
        copy(updatedAt = now, revision = revision + 1, history = (history + BoardEvent(now, by, change)).takeLast(50))

    private fun BoardBlocker.checked(id: String): BoardBlocker = when (this) {
        is BoardBlocker.Task -> {
            require(task != id) { "A task cannot wait on itself" }
            var next: String? = task
            val seen = mutableSetOf(id)
            while (next != null) {
                require(seen.add(next)) { "$id and $task would wait on each other" }
                next = (get(next).blocker as? BoardBlocker.Task)?.task
            }
            this
        }
        is BoardBlocker.Owner -> BoardBlocker.Owner(owner.bounded("owner", 100).also { require(it.isNotEmpty()) { "Name who unblocks it" } },
            action.bounded("action", 300).also { require(it.isNotEmpty()) { "Name the action that unblocks it" } })
    }

    private fun BoardActor.checked() = also { require(name.isNotBlank() && name.length <= 100) { "Name who makes the change" } }

    private fun BoardLinks.checked() = also {
        listOfNotNull(mission, issue, pullRequest).forEach { require(it.isNotBlank() && it.length <= 500) { "Links are 1 to 500 characters" } }
    }

    private fun String.title(): String = trim().also { require(it.isNotEmpty() && it.length <= 200 && '\n' !in it) { "A title is one line of 1 to 200 characters" } }

    private fun String.bounded(name: String, limit: Int): String = trim().also { require(it.length <= limit) { "$name is at most $limit characters" } }

    private fun List<String>.lines(name: String, count: Int, limit: Int): List<String> = map { it.bounded(name, limit) }.filter(String::isNotEmpty).also {
        require(it.size <= count) { "$name has at most $count items" }
    }

    private fun List<String>.refs(): List<String> = distinct().also { refs ->
        require(refs.size <= 50) { "A task links at most 50 runs and sessions" }
        refs.forEach { require(BoardRefs.pattern.matches(it)) { "$it is not a run or session: use hangar:<thread>, claude:<session> or codex:<session>" } }
    }

    private companion object {
        val TaskFile = Regex("T[0-9]{1,9}\\.json")
    }
}
