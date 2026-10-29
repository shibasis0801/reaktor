package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class PlanStatus(val label: String) { Todo("To do"), Doing("In progress"), Done("Done"), Dropped("Won't do") }

@Serializable
enum class PlanPriority { Low, Normal, High, Urgent }

@Serializable(with = PlanKindSerializer::class)
enum class PlanKind(val label: String, val words: Set<String>) {
    Component("Component", setOf("area", "module", "service", "screen", "system", "subsystem", "layer", "part")),
    Task("Task", setOf("step", "todo", "work", "change")),
    Check("Check", setOf("test", "tests", "flow", "verify", "verification", "proof")),
    Decision("Decision", setOf("question", "choice", "decide", "open question"));

    companion object {
        fun of(word: String): PlanKind = word.trim().lowercase().let { said -> entries.firstOrNull { it.name.lowercase() == said || said in it.words } ?: Task }
    }
}

object PlanKindSerializer : KSerializer<PlanKind> {
    override val descriptor = PrimitiveSerialDescriptor("dev.shibasis.reaktor.conductor.workspace.PlanKind", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: PlanKind) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): PlanKind = PlanKind.of(decoder.decodeString())
}

@Serializable
data class PlanRelation(val to: String, val label: String)

@Serializable
data class PlanPoint(val x: Double, val y: Double)

@Serializable
data class PlanItem(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: PlanStatus = PlanStatus.Todo,
    val parent: String? = null,
    val after: List<String> = emptyList(),
    val refs: List<String> = emptyList(),
    val seat: String? = null,
    val at: PlanPoint? = null,
    val revision: Long = 0,
    val by: BoardActor? = null,
    val changedAt: Long = 0,
    val kind: PlanKind = PlanKind.Task,
    val relations: List<PlanRelation> = emptyList(),
    val priority: PlanPriority = PlanPriority.Normal,
    val reason: String = "",
    val replacement: String? = null,
)

@Serializable
data class PlanSeat(
    val id: String,
    val profile: String,
    val items: List<String>,
    val by: BoardActor,
    val at: Long,
    val run: String? = null,
    val session: String? = null,
)

@Serializable
data class PlanChange(val revision: Long, val at: Long, val by: BoardActor, val summary: List<String>)

@Serializable
data class WorkPlan(
    val thread: String,
    val title: String,
    val goal: String = "",
    val items: List<PlanItem> = emptyList(),
    val seats: List<PlanSeat> = emptyList(),
    val revision: Long = 0,
    val next: Int = 1,
    val createdBy: BoardActor,
    val createdAt: Long,
    val updatedAt: Long,
    val changes: List<PlanChange> = emptyList(),
) {
    fun item(id: String): PlanItem? = items.firstOrNull { it.id == id }

    fun seat(id: String): PlanSeat? = seats.firstOrNull { it.id == id }

    fun under(id: String): Set<String> {
        val children = items.groupBy { it.parent }
        val found = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (found.add(next)) children[next].orEmpty().forEach { queue.addLast(it.id) }
        }
        return found
    }

    fun parts(id: String): List<PlanItem> = items.filter { it.parent == id }

    fun leaves(): List<PlanItem> {
        val parents = items.mapNotNullTo(HashSet()) { it.parent }
        return items.filter { it.id !in parents }
    }

    fun ready(): List<PlanItem> = leaves().filter { item ->
        item.status == PlanStatus.Todo && item.after.all { state(it) in setOf(PlanStatus.Done, PlanStatus.Dropped) } &&
            generateSequence(item.parent?.let(this::item)) { it.parent?.let(this::item) }.take(items.size).none { it.status == PlanStatus.Dropped }
    }.sortedByDescending { it.priority.ordinal }

    fun components(): List<PlanItem> {
        val parents = items.mapNotNullTo(HashSet()) { it.parent }
        return items.filter { it.kind == PlanKind.Component || it.id in parents }
    }

    fun state(id: String): PlanStatus {
        val item = item(id) ?: return PlanStatus.Todo
        val parents = items.mapNotNullTo(HashSet()) { it.parent }
        if (item.status == PlanStatus.Dropped || id !in parents) return item.status
        val open = under(id).minus(id).mapNotNull(::item).filter { it.id !in parents && it.status != PlanStatus.Dropped }
        return when {
            item.status == PlanStatus.Done || open.isNotEmpty() && open.all { it.status == PlanStatus.Done } -> PlanStatus.Done
            open.isEmpty() -> PlanStatus.Dropped
            open.any { it.status != PlanStatus.Todo } || item.status == PlanStatus.Doing -> PlanStatus.Doing
            else -> PlanStatus.Todo
        }
    }

    fun news(since: Long, reader: BoardActor?): List<PlanChange> = changes.filter { it.revision > since && !it.by.same(reader) }

    fun summary(): PlanSummary = leaves().let { work ->
        PlanSummary(thread, title, revision, items.size,
            PlanStatus.entries.associateWith { status -> work.count { it.status == status } }.filterValues { it > 0 },
            seats.size, updatedAt, changes.lastOrNull()?.by, components().size)
    }
}

@Serializable
data class PlanSummary(
    val thread: String,
    val title: String,
    val revision: Long,
    val items: Int,
    val statuses: Map<PlanStatus, Int>,
    val seats: Int,
    val updatedAt: Long,
    val by: BoardActor? = null,
    val components: Int = 0,
) {
    fun count(status: PlanStatus) = statuses[status] ?: 0
}

@Serializable
sealed interface PlanOp {
    @Serializable @SerialName("add")
    data class Add(
        val title: String,
        val key: String? = null,
        val detail: String = "",
        val status: PlanStatus = PlanStatus.Todo,
        val parent: String? = null,
        val after: List<String> = emptyList(),
        val refs: List<String> = emptyList(),
        val at: PlanPoint? = null,
        val kind: PlanKind = PlanKind.Task,
        val priority: PlanPriority = PlanPriority.Normal,
        val reason: String = "",
        val replacement: String? = null,
    ) : PlanOp

    @Serializable @SerialName("update")
    data class Update(
        val item: String,
        val title: String? = null,
        val detail: String? = null,
        val parent: String? = null,
        val root: Boolean = false,
        val refs: List<String>? = null,
        val at: PlanPoint? = null,
        val kind: PlanKind? = null,
        val priority: PlanPriority? = null,
        val reason: String? = null,
        val replacement: String? = null,
    ) : PlanOp

    @Serializable @SerialName("status")
    data class Status(val item: String, val status: PlanStatus, val reason: String? = null, val replacement: String? = null) : PlanOp

    @Serializable @SerialName("connect")
    data class Connect(val from: String, val to: String) : PlanOp

    @Serializable @SerialName("disconnect")
    data class Disconnect(val from: String, val to: String) : PlanOp

    @Serializable @SerialName("relate")
    data class Relate(val from: String, val to: String, val label: String) : PlanOp

    @Serializable @SerialName("unrelate")
    data class Unrelate(val from: String, val to: String) : PlanOp

    @Serializable @SerialName("remove")
    data class Remove(val item: String) : PlanOp
}

@Serializable
data class PlanPatch(
    val ops: List<PlanOp> = emptyList(),
    val title: String? = null,
    val goal: String? = null,
    val base: Long? = null,
    val seat: String? = null,
)

@Serializable
data class PlanRefusal(val op: Int, val item: String? = null, val reason: String)

@Serializable
data class PlanApplied(val plan: WorkPlan, val refused: List<PlanRefusal> = emptyList(), val news: List<PlanChange> = emptyList())

@Serializable
data class PlanRead(val plan: WorkPlan, val news: List<PlanChange> = emptyList())

@Serializable
data class PlanAttached(val plan: WorkPlan, val seat: PlanSeat, val run: AgentRunRecord)

object PlanBrief {
    const val Ops = "ops such as {type: \"add\", key: \"store\", kind: \"Component\", title: \"Plan store\", refs: [\"module:reaktor-conductor\", \"symbol:WorkPlanStore\"]}, " +
        "{type: \"add\", key: \"relations\", title, detail, parent: \"store\", after: [\"model\"], refs: [\"file:path\", \"graph:Name\", \"op:plan.write\", \"flow:F-AGENT-58\"]}, " +
        "{type: \"relate\", from: \"api\", to: \"store\", label: \"writes\"}, {type: \"status\", item, status: \"Doing\" | \"Done\" | \"Dropped\"}, " +
        "{type: \"update\", item, title, detail, kind, parent, refs, priority: \"Low\" | \"Normal\" | \"High\" | \"Urgent\"} and {type: \"connect\", from, to}, where to waits on from"

    const val Design = "Draw a detailed high-level design, not a to-do list: a Component item for each area the change touches, named after its module, graph, service or screen; " +
        "under each one (parent) the tasks that change it, a Check item for each test or flow that proves it and a Decision item for each choice the owner has to make; " +
        "relate components by what passes between them (calls, reads, writes, renders, serves); use after for what has to finish first; " +
        "and give every item refs to the files, symbols, modules, graphs, operations and flows it touches, found with atlas_query rather than guessed. " +
        "A real change has 3 to 8 components and 15 to 60 items."

    const val Upkeep = "Keep it current while you work: mark the item you are on Doing before you edit for it and Done or Dropped when it ends, since a component's status follows its parts; " +
        "add the parts, checks and decisions you discover, and move refs when the work moves. Choose the highest-priority ready item first: Urgent, High, Normal, then Low. Preserve planned history: close superseded items as Dropped with a nonblank reason and optional replacement item id; do not remove them."

    fun thread(thread: String): String =
        "Reaktor keeps a plan for this thread: the high-level design the owner watches and edits in Hangar as a DAG, a Kanban and a reaktor-graph joined to the code atlas, " +
            "and your running checklist. Draw it before your first change with the reaktor kernel tool: {operation: \"plan.write\", arguments: " +
            "{thread: \"$thread\", title: \"the request in a few words\", ops: [...]}} with $Ops. $Design $Upkeep Pass base, the plan revision you last read. " +
            "Between steps read {operation: \"plan.read\", arguments: {thread: \"$thread\", since: <that revision>}}: news lists what the owner or other agents changed; follow it."

    fun seat(plan: WorkPlan, seat: PlanSeat, agent: String, note: String): String = buildString {
        appendLine("You are $agent, attached to part of the plan “${plan.title}” (thread ${plan.thread}) as seat ${seat.id}.")
        if (plan.goal.isNotBlank()) appendLine("Goal: ${plan.goal}")
        appendLine("Your items, with everything under them:")
        seat.items.mapNotNull(plan::item).forEach { item ->
            appendLine("- ${item.id} “${item.title}”${item.detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
        }
        val covered = seat.items.flatMapTo(HashSet()) { plan.under(it) }
        val ready = plan.ready().filter { it.id in covered }
        if (ready.isNotEmpty()) appendLine("Ready, highest priority first: " + ready.joinToString { "${it.id} (${it.priority})" })
        if (note.isNotBlank()) appendLine(note.trim())
        append("Work through your items in dependency order. Read the plan with the reaktor kernel tool {operation: \"plan.read\", arguments: {thread: \"${plan.thread}\"}} ")
        append("and change it with {operation: \"plan.write\", arguments: {thread: \"${plan.thread}\", seat: \"${seat.id}\", base: <revision you read>, ops: [...]}} using $Ops. ")
        append("Break your items into the components, tasks, checks and decisions you find, relate them, and give each refs found with atlas_query. $Upkeep ")
        append("People and other agents own the rest of the plan; plan.read with since lists their changes. Stay inside your items.")
    }
}

fun BoardActor.same(other: BoardActor?): Boolean = other != null && kind == other.kind &&
    if (session != null || other.session != null) session == other.session else name == other.name

object PlanRules {
    const val MaxItems = 200
    const val MaxOps = 100
    const val MaxChanges = 200
    const val MaxRefs = 20
    const val MaxRelations = 20
    val Key = Regex("[a-z][a-z0-9-]{0,39}")
    val Ref = Regex("(file|symbol|module|graph|op|flow|task|url):\\S.{0,299}")

    fun start(thread: String, patch: PlanPatch, by: BoardActor, now: Long): WorkPlan {
        require(BoardRefs.pattern.matches(thread)) { "$thread is not a thread: use hangar:, claude:, codex: or agy: with its id" }
        require(by.kind == BoardActorKind.Person || by.session == null || by.session == thread || thread.startsWith(BoardRefs.Hangar)) {
            "An agent starts a plan for its own thread only; read $thread's plan with plan.read or ask a person to attach you to it"
        }
        val title = BoardText.title(patch.title ?: (patch.ops.firstOrNull() as? PlanOp.Add)?.title ?: error("Name the plan with title"))
        return WorkPlan(thread, title, createdBy = by, createdAt = now, updatedAt = now)
    }

    fun apply(plan: WorkPlan, patch: PlanPatch, by: BoardActor, now: Long): PlanApplied {
        require(patch.ops.size <= MaxOps) { "A patch has at most $MaxOps ops; send the rest in another patch" }
        val revision = plan.revision + 1
        val items = LinkedHashMap(plan.items.associateBy { it.id })
        val refused = mutableListOf<PlanRefusal>()
        val summary = mutableListOf<String>()
        var next = plan.next
        var seats = plan.seats
        val claimed = patch.seat?.let { id ->
            val seat = plan.seat(id) ?: error("$id is not a seat on this plan")
            require(seat.session == null || by.session == null || seat.session == by.session) { "$id belongs to ${seat.session}" }
            if (seat.session == null && by.session != null) seats = seats.map { if (it.id == id) it.copy(session = by.session) else it }
            seat
        }
        val seatedItems = claimed?.items.orEmpty().flatMapTo(HashSet()) { root -> plan.under(root) }
        val threadAgent = by.kind == BoardActorKind.Agent && by.session == plan.thread

        fun ownedBy(id: String): PlanSeat? {
            var current = items[id]
            val seen = HashSet<String>()
            while (current != null && seen.add(current.id)) {
                current.seat?.let { seat -> return plan.seat(seat) }
                current = current.parent?.let(items::get)
            }
            return null
        }

        fun allowed(id: String): String? {
            if (by.kind == BoardActorKind.Person || threadAgent) return null
            if (claimed != null) return if (id in seatedItems || items[id]?.let { item -> generateSequence(item) { it.parent?.let(items::get) }.any { it.id in seatedItems } } == true) null
                else "$id is outside your seat ${claimed.id}; it covers ${claimed.items.joinToString()}"
            val owner = ownedBy(id) ?: return null
            return if (owner.session != null && owner.session == by.session) null else "$id belongs to seat ${owner.id} (${owner.profile}); only that agent, the thread's agent or a person changes it"
        }

        fun fresh(id: String): String? {
            val base = patch.base ?: return null
            val item = items[id] ?: return null
            return if (item.revision > base && item.by?.same(by) == false)
                "$id changed at revision ${item.revision} by ${item.by.name} after the revision $base you read; read the plan again and decide" else null
        }

        fun cycles(child: String, parent: String?): Boolean {
            var current = parent
            val seen = HashSet<String>()
            while (current != null && seen.add(current)) {
                if (current == child) return true
                current = items[current]?.parent
            }
            return false
        }

        fun waitsOn(item: String, prerequisite: String): Boolean {
            val queue = ArrayDeque(listOf(prerequisite))
            val seen = HashSet<String>()
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                if (key == item) return true
                if (seen.add(key)) queue.addAll(items[key]?.after.orEmpty())
            }
            return false
        }

        fun refs(values: List<String>): List<String> = values.map(String::trim).filter(String::isNotEmpty).distinct().also { list ->
            require(list.size <= MaxRefs) { "An item links at most $MaxRefs things" }
            list.firstOrNull { !Ref.matches(it) }?.let { error("$it is not a link: use file:, symbol:, module:, graph:, op:, flow:, task: or url:") }
        }

        fun closing(status: PlanStatus, reason: String, replacement: String?, id: String): Pair<String, String?> {
            val why = BoardText.bounded(reason, "A closing reason", 1_000)
            require(status != PlanStatus.Dropped || why.isNotBlank()) { "Give a closing reason when dropping $id" }
            val next = replacement?.trim()?.takeIf(String::isNotEmpty)
            require(next == null || next != id && next in items) { "A replacement must be another item on this plan" }
            return why to next
        }

        fun touch(item: PlanItem) = item.copy(revision = revision, by = by, changedAt = now)

        patch.ops.forEachIndexed { index, op ->
            val outcome = runCatching {
                when (op) {
                    is PlanOp.Add -> {
                        require(items.size < MaxItems) { "A plan has at most $MaxItems items; group finished work under one item or start a new thread" }
                        val key = op.key?.also { require(Key.matches(it)) { "A key is 1 to 40 lowercase letters, digits or dashes, starting with a letter" } }
                        require(key == null || key !in items) { "$key exists; send update or status for it" }
                        val parent = op.parent?.also { require(it in items) { "Parent $it is not on the plan" } }
                        if (parent != null) allowed(parent)?.let(::error)
                        else require(claimed == null) { "Add your items under one of your seat's items: ${claimed!!.items.joinToString()}" }
                        val after = op.after.distinct().onEach { require(it in items) { "$it is not on the plan" } }
                        val title = BoardText.title(op.title)
                        val detail = BoardText.bounded(op.detail, "A detail", 4_000)
                        val links = refs(op.refs)
                        val id = key ?: generateSequence(next) { it + 1 }.map { "p$it" }.first { it !in items }.also { next = it.removePrefix("p").toInt() + 1 }
                        val (reason, replacement) = closing(op.status, op.reason, op.replacement, id)
                        items[id] = touch(PlanItem(id, title, detail, op.status, parent, after, links, at = op.at, kind = op.kind, priority = op.priority,
                            reason = reason, replacement = replacement))
                        "Added $id “$title”"
                    }
                    is PlanOp.Update -> {
                        val item = items[op.item] ?: error("${op.item} is not on the plan")
                        (allowed(item.id) ?: fresh(item.id))?.let(::error)
                        val parent = when {
                            op.root -> null
                            op.parent != null -> op.parent.also { require(it in items) { "Parent $it is not on the plan" }; require(!cycles(item.id, it)) { "${item.id} cannot sit under its own part $it" } }
                            else -> item.parent
                        }
                        val (reason, replacement) = if (op.reason != null || op.replacement != null)
                            closing(item.status, op.reason ?: item.reason, op.replacement ?: item.replacement, item.id) else item.reason to item.replacement
                        val changed = item.copy(
                            title = op.title?.let(BoardText::title) ?: item.title,
                            detail = op.detail?.let { BoardText.bounded(it, "A detail", 4_000) } ?: item.detail,
                            parent = parent,
                            refs = op.refs?.let(::refs) ?: item.refs,
                            at = op.at ?: item.at,
                            kind = op.kind ?: item.kind,
                            priority = op.priority ?: item.priority,
                            reason = reason,
                            replacement = replacement,
                        )
                        if (changed == item) null else {
                            items[item.id] = if (changed.copy(at = item.at) == item) changed else touch(changed)
                            when {
                                changed.title != item.title -> "Renamed ${item.id} “${changed.title}”"
                                changed.copy(at = item.at) == item -> null
                                else -> "Edited ${item.id}"
                            }
                        }
                    }
                    is PlanOp.Status -> {
                        val item = items[op.item] ?: error("${op.item} is not on the plan")
                        (allowed(item.id) ?: fresh(item.id))?.let(::error)
                        val (reason, replacement) = closing(op.status, op.reason ?: item.reason, op.replacement ?: item.replacement, item.id)
                        val changed = item.copy(status = op.status, reason = reason, replacement = replacement)
                        if (changed == item) null else {
                            items[item.id] = touch(changed)
                            "${item.id} → ${op.status.label}"
                        }
                    }
                    is PlanOp.Connect -> {
                        val to = items[op.to] ?: error("${op.to} is not on the plan")
                        require(op.from in items) { "${op.from} is not on the plan" }
                        (allowed(to.id) ?: fresh(to.id))?.let(::error)
                        require(op.from != op.to && !waitsOn(op.to, op.from)) { "${op.to} waiting on ${op.from} would make a cycle" }
                        if (op.from in to.after) null else {
                            items[to.id] = touch(to.copy(after = to.after + op.from))
                            "${to.id} waits on ${op.from}"
                        }
                    }
                    is PlanOp.Disconnect -> {
                        val to = items[op.to] ?: error("${op.to} is not on the plan")
                        (allowed(to.id) ?: fresh(to.id))?.let(::error)
                        if (op.from !in to.after) null else {
                            items[to.id] = touch(to.copy(after = to.after - op.from))
                            "${to.id} no longer waits on ${op.from}"
                        }
                    }
                    is PlanOp.Relate -> {
                        val from = items[op.from] ?: error("${op.from} is not on the plan")
                        require(op.to in items) { "${op.to} is not on the plan" }
                        require(op.from != op.to) { "An item does not relate to itself" }
                        (allowed(from.id) ?: fresh(from.id))?.let(::error)
                        val label = op.label.replace(Regex("\\s+"), " ").trim().also { require(it.length in 1..40) { "Name a relation in 1 to 40 characters, such as calls, reads or renders" } }
                        val kept = from.relations.filter { it.to != op.to }
                        if (from.relations.any { it.to == op.to && it.label == label }) null else {
                            require(kept.size < MaxRelations) { "An item relates to at most $MaxRelations others" }
                            items[from.id] = touch(from.copy(relations = kept + PlanRelation(op.to, label)))
                            "${from.id} $label ${op.to}"
                        }
                    }
                    is PlanOp.Unrelate -> {
                        val from = items[op.from] ?: error("${op.from} is not on the plan")
                        (allowed(from.id) ?: fresh(from.id))?.let(::error)
                        if (from.relations.none { it.to == op.to }) null else {
                            items[from.id] = touch(from.copy(relations = from.relations.filter { it.to != op.to }))
                            "${from.id} no longer relates to ${op.to}"
                        }
                    }
                    is PlanOp.Remove -> {
                        require(by.kind == BoardActorKind.Person) { "Keep planned history: close the item as Dropped with a reason instead of removing it" }
                        val item = items[op.item] ?: error("${op.item} is not on the plan")
                        (allowed(item.id) ?: fresh(item.id))?.let(::error)
                        items.remove(item.id)
                        items.values.toList().forEach { other ->
                            if (other.parent == item.id || item.id in other.after || other.relations.any { it.to == item.id } || other.replacement == item.id)
                                items[other.id] = other.copy(parent = if (other.parent == item.id) item.parent else other.parent, after = other.after - item.id,
                                    relations = other.relations.filter { it.to != item.id }, replacement = other.replacement?.takeUnless { it == item.id })
                        }
                        "Removed ${item.id} “${item.title}”"
                    }
                }
            }
            outcome.onSuccess { line -> line?.let(summary::add) }
                .onFailure { failure -> refused += PlanRefusal(index, op.target(), failure.message ?: "Refused") }
        }
        val title = patch.title?.let(BoardText::title)?.takeIf { it != plan.title }
        val goal = patch.goal?.let { BoardText.bounded(it, "A goal", 2_000) }?.takeIf { it != plan.goal }
        if (title != null) summary += "Renamed the plan “$title”"
        if (goal != null) summary += "Changed the goal"
        val news = plan.news(patch.base ?: plan.revision, by)
        if (summary.isEmpty() && seats == plan.seats && items.values.toList() == plan.items) return PlanApplied(plan, refused, news)
        val changed = plan.copy(
            title = title ?: plan.title, goal = goal ?: plan.goal, items = items.values.toList(), seats = seats,
            revision = revision, next = next, updatedAt = now,
            changes = if (summary.isEmpty()) plan.changes else (plan.changes + PlanChange(revision, now, by, summary)).takeLast(MaxChanges),
        )
        return PlanApplied(changed, refused, news)
    }

    fun attach(plan: WorkPlan, seat: PlanSeat, now: Long): WorkPlan {
        require(seat.by.kind == BoardActorKind.Person) { "Only a person attaches an agent to part of a plan" }
        require(seat.items.isNotEmpty()) { "Choose the items the agent takes" }
        seat.items.forEach { id -> require(plan.item(id) != null) { "$id is not on the plan" } }
        val covered = seat.items.flatMapTo(HashSet()) { plan.under(it) }
        val revision = plan.revision + 1
        return plan.copy(
            seats = plan.seats + seat,
            items = plan.items.map { if (it.id in seat.items) it.copy(seat = seat.id, revision = revision, by = seat.by, changedAt = now) else it },
            revision = revision, updatedAt = now,
            changes = (plan.changes + PlanChange(revision, now, seat.by, listOf("Attached ${seat.profile} to ${seat.items.joinToString()} (${covered.size} items)"))).takeLast(MaxChanges),
        )
    }

    private fun PlanOp.target(): String? = when (this) {
        is PlanOp.Add -> key
        is PlanOp.Update -> item
        is PlanOp.Status -> item
        is PlanOp.Connect -> to
        is PlanOp.Disconnect -> to
        is PlanOp.Relate -> from
        is PlanOp.Unrelate -> from
        is PlanOp.Remove -> item
    }
}
