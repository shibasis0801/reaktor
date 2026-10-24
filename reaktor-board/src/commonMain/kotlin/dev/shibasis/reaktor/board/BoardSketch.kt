package dev.shibasis.reaktor.board

import kotlinx.serialization.Serializable
import kotlin.math.round

@Serializable
data class SketchCard(val ref: String, val title: String, val at: BoardPoint)

@Serializable
data class SketchLink(val from: String, val to: String)

@Serializable
data class SketchNote(val text: String, val at: BoardPoint)

data class SketchMark(
    val id: String,
    val type: String,
    val x: Double,
    val y: Double,
    val width: Double = 0.0,
    val height: Double = 0.0,
    val text: String = "",
    val container: String? = null,
    val start: String? = null,
    val end: String? = null,
    val points: List<BoardPoint> = emptyList(),
)

data class SketchRefusal(val mark: String, val reason: String)

data class SketchReading(
    val lane: String,
    val cards: List<SketchCard> = emptyList(),
    val links: List<SketchLink> = emptyList(),
    val notes: List<SketchNote> = emptyList(),
    val refused: List<SketchRefusal> = emptyList(),
    val loose: Int = 0,
    val consumed: Set<String> = emptySet(),
    val problem: String? = null,
) {
    val edit: BoardEdit.Sketch?
        get() = if (problem != null || (cards.isEmpty() && links.isEmpty() && notes.isEmpty())) null else BoardEdit.Sketch(lane, cards, links, notes)
}

private val Containers = setOf("rectangle", "ellipse", "diamond")
private const val Reach = 8.0
private const val NoteLimit = 2000

private fun BoardPoint.near(left: Double, top: Double, width: Double, height: Double) =
    x >= left - Reach && x <= left + width + Reach && y >= top - Reach && y <= top + height + Reach

private fun SketchMark.holds(at: BoardPoint) = at.near(x, y, width, height)

private fun SketchMark.ends(): Pair<BoardPoint, BoardPoint>? =
    if (points.size < 2) null else BoardPoint(x + points.first().x, y + points.first().y) to BoardPoint(x + points.last().x, y + points.last().y)

private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()

private fun placed(x: Double, y: Double) = BoardPoint(round(x), round(y))

fun Board.read(marks: List<SketchMark>, positions: Map<String, BoardPoint>): SketchReading {
    val lane = creatable.firstOrNull()?.id ?: lanes.firstOrNull()?.id.orEmpty()
    val labels = marks.filter { it.type == "text" && it.container != null }.associateBy { it.container!! }
    val boxes = marks.filter { it.type in Containers }
        .mapNotNull { box -> labels[box.id]?.let { clean(it.text) }?.takeIf(String::isNotEmpty)?.let { box to it } }
        .sortedWith(compareBy({ it.first.y }, { it.first.x }))
    val prefix = generateSequence("sketch-") { "_$it" }.first { prefix -> items.none { it.key.startsWith(prefix) } }
    val cards = boxes.mapIndexed { index, (box, title) -> SketchCard("$prefix${index + 1}", title.take(titleLimit), placed(box.x, box.y)) }
    val texts = if (!this.notes) emptyList() else marks.filter { it.type == "text" && it.container == null && clean(it.text).isNotEmpty() }
    val notes = texts.map { SketchNote(it.text.trim().take(NoteLimit), placed(it.x, it.y)) }
    if (locked.isNotBlank()) return SketchReading(lane, problem = locked)
    if (cards.isNotEmpty() && creatable.isEmpty()) return SketchReading(lane, problem = "${words.manyTitle} cannot be created here.")
    val refs = boxes.zip(cards).associate { (box, card) -> box.first.id to card.ref }
    val shown = positions.keys.filter { item(it) != null }
    val bound = shown.associateBy { BoardIds.card(it) }
    fun resolve(binding: String?, at: BoardPoint?): String? {
        binding?.let { id -> (refs[id] ?: BoardIds.cardOf(id)?.let(bound::get))?.let { return it } }
        if (at == null) return null
        boxes.lastOrNull { it.first.holds(at) }?.let { return refs.getValue(it.first.id) }
        return shown.lastOrNull { key -> positions.getValue(key).let { at.near(it.x, it.y, BoardGeometry.CardWidth, BoardGeometry.CardHeight) } }
    }
    val after = HashMap<String, MutableList<String>>()
    items.forEach { after[it.key] = it.after.toMutableList() }
    cards.forEach { after[it.ref] = mutableListOf() }
    fun title(key: String) = cards.firstOrNull { it.ref == key }?.title ?: item(key)?.title ?: key
    fun cycles(target: String, prerequisite: String): Boolean {
        val queue = ArrayDeque(listOf(prerequisite))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (key == target) return true
            if (seen.add(key)) queue.addAll(after[key].orEmpty())
        }
        return false
    }
    val links = ArrayList<SketchLink>()
    val refused = ArrayList<SketchRefusal>()
    val linked = HashSet<String>()
    var loose = 0
    marks.filter { it.type == "arrow" }.sortedWith(compareBy({ it.y }, { it.x })).forEach { arrow ->
        val ends = arrow.ends()
        val from = resolve(arrow.start, ends?.first)
        val to = resolve(arrow.end, ends?.second)
        if (from == null || to == null) { loose++; return@forEach }
        val target = item(to)
        val reason = when {
            from == to -> "${words.anOne.replaceFirstChar(Char::uppercaseChar)} cannot wait on itself."
            target != null && !target.editable -> "${quote(target.title)} is read only."
            from in after.getValue(to) -> "${quote(title(to))} already waits on ${quote(title(from))}."
            after.getValue(to).size >= limit -> "${words.anOne.replaceFirstChar(Char::uppercaseChar)} can have up to $limit ${if (limit == 1) words.after else words.afters}."
            cycles(to, from) -> "That arrow would create a cycle."
            else -> null
        }
        if (reason != null) { refused += SketchRefusal(arrow.id, reason); return@forEach }
        after.getValue(to) += from
        links += SketchLink(from, to)
        linked += arrow.id
    }
    val consumed = buildSet {
        boxes.forEach { (box, _) -> add(box.id); labels[box.id]?.let { add(it.id) } }
        texts.forEach { add(it.id) }
        addAll(linked)
        marks.filter { it.container in linked }.forEach { add(it.id) }
    }
    return SketchReading(lane, cards, links, notes, refused, loose, consumed)
}

fun Board.summary(reading: SketchReading): String {
    reading.problem?.let { return it }
    val parts = listOfNotNull(
        reading.cards.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) words.one else words.many}" },
        reading.links.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) words.after else words.afters}" },
        reading.notes.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "note" else "notes"}" },
    )
    val head = if (parts.isEmpty()) "Nothing to turn into ${words.many} yet. Draw a box with a title inside, or an arrow between two ${words.many}."
        else "Added ${parts.joinToString(", ")}."
    val skipped = reading.refused.firstOrNull()?.let { " ${reading.refused.size} ${if (reading.refused.size == 1) "arrow stays" else "arrows stay"} on the sketch: ${it.reason}" }.orEmpty()
    val loose = if (reading.loose == 0) "" else " ${reading.loose} ${if (reading.loose == 1) "arrow does" else "arrows do"} not join two ${words.many}."
    return head + skipped + loose
}

private fun quote(value: String) = "“$value”"
