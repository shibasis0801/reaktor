package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
private data class PlanReadCursor(val thread: String, val revision: Long, val reader: String, val frame: String? = null,
                                  val offset: Int = 0, val fragment: Int = 0)

object PlanReadPage {
    const val DefaultBytes = 65_536
    const val MaximumBytes = 262_144
    private val json = Json { encodeDefaults = true }

    fun thread(continuation: String): String = json.decodeFromString(PlanReadCursor.serializer(), continuation).thread

    fun answer(plan: WorkPlan, reader: BoardActor, extra: JsonObject = JsonObject(emptyMap()), frame: String? = null,
               continuation: String? = null, budgetBytes: Int? = null): JsonObject {
        val budget = (budgetBytes ?: plan.limits.readBytes).coerceAtMost(minOf(plan.limits.maxReadBytes, MaximumBytes))
        require(budget >= 4096) { "Use an answer budget of at least 4096 bytes" }
        val identity = PlanPeople.ref(reader) + (reader.session ?: "")
        val cursor = continuation?.let { json.decodeFromString(PlanReadCursor.serializer(), it) }
            ?: PlanReadCursor(plan.thread, plan.revision, identity, frame)
        if (cursor.thread != plan.thread || cursor.revision != plan.revision || cursor.reader != identity || cursor.frame != frame)
            return buildJsonObject { put("restart", true); put("reason", "The plan changed or the selection changed; restart this read"); put("revision", plan.revision) }
        val order = order(plan, reader, frame)
        require(cursor.offset in 0..order.size && cursor.fragment >= 0) { "Invalid plan continuation" }
        val remaining = order.drop(cursor.offset)
        fun token(offset: Int, fragment: Int = 0) = json.encodeToString(PlanReadCursor.serializer(), cursor.copy(offset = offset, fragment = fragment))
        fun envelope(items: List<PlanItem>, next: String?, fragment: JsonObject? = null): JsonObject {
            val ids = items.mapTo(HashSet()) { it.id }
            val seats = items.mapNotNullTo(HashSet()) { it.seat }
            val page = plan.copy(items = items, seats = plan.seats.filter { it.id in seats }, changes = emptyList())
            return buildJsonObject {
                put("plan", json.encodeToJsonElement(WorkPlan.serializer(), page))
                put("projection", "agent-page")
                put("complete", cursor.offset == 0 && next == null && fragment == null)
                put("total", order.size)
                put("offset", cursor.offset)
                val claims = (extra["claims"] as? JsonObject)?.get("claims") as? JsonObject
                if (claims != null) put("claims", buildJsonObject { put("claims", JsonObject(claims.filterKeys { it in ids })) })
                (extra["ready"] as? JsonArray)?.let { ready -> put("ready", JsonArray(ready.map { compactCandidate(it) })) }
                (extra["news"] as? JsonArray)?.let { news ->
                    val shown = news.take(1)
                    put("news", JsonArray(shown))
                    if (shown.size < news.size) {
                        put("newsTruncated", true)
                        shown.lastOrNull()?.jsonObject?.get("revision")?.let { put("newsSince", it) }
                    }
                }
                extra.filterKeys { it !in setOf("plan", "claims", "ready", "news") }.forEach { (key, value) -> put(key, value) }
                if (next != null) put("continuation", next)
                if (fragment != null) put("fragment", fragment)
            }
        }
        var low = 0
        var high = remaining.size
        if (cursor.fragment == 0) {
            while (low < high) {
                val count = (low + high + 1) / 2
                val next = (cursor.offset + count).takeIf { it < order.size }?.let { token(it) }
                if (envelope(remaining.take(count), next).toString().encodeToByteArray().size <= budget) low = count else high = count - 1
            }
            if (low > 0 || remaining.isEmpty()) return envelope(remaining.take(low),
                (cursor.offset + low).takeIf { it < order.size }?.let { token(it) }).also {
                require(it.toString().encodeToByteArray().size <= budget) { "The plan metadata needs a larger answer budget" }
            }
        }
        val item = remaining.first()
        val encoded = json.encodeToString(PlanItem.serializer(), item)
        require(cursor.fragment < encoded.length) { "Invalid item continuation" }
        fun part(end: Int): JsonObject {
            val next = if (end < encoded.length) token(cursor.offset, end) else
                (cursor.offset + 1).takeIf { it < order.size }?.let { token(it) }
            return envelope(emptyList(), next, buildJsonObject {
                put("item", item.id); put("encoding", "json"); put("offset", cursor.fragment)
                put("text", encoded.substring(cursor.fragment, end)); put("totalChars", encoded.length)
            })
        }
        low = cursor.fragment
        high = encoded.length
        while (low < high) {
            val end = (low + high + 1) / 2
            if (part(end).toString().encodeToByteArray().size <= budget) low = end else high = end - 1
        }
        require(low > cursor.fragment) { "The plan metadata needs a larger answer budget" }
        return part(low)
    }

    fun compactCandidate(value: JsonElement): JsonElement {
        val candidate = value as? JsonObject ?: return value
        val item = candidate["item"] as? JsonObject ?: return value
        return JsonObject(candidate + mapOf("item" to JsonObject(item.filterKeys { it in setOf("id", "title", "status", "kind", "priority", "parent", "revision") }),
            "itemPartial" to JsonPrimitive(true), "itemFrame" to (item["id"] ?: JsonNull)))
    }

    private fun order(plan: WorkPlan, reader: BoardActor, frame: String?): List<PlanItem> {
        if (frame != null) {
            require(plan.item(frame) != null) { "$frame is not a frame in this plan" }
            val ids = plan.under(frame)
            return plan.items.filter { it.id in ids }
        }
        val keyed = plan.items.associateBy { it.id }
        fun lineage(item: PlanItem) = generateSequence(item) { it.parent?.let(keyed::get) }.take(keyed.size)
        fun assigned(item: PlanItem) = lineage(item).map(PlanPeople::of).firstOrNull { it.isNotEmpty() }.orEmpty()
        fun ours(actor: BoardActor) = actor.same(reader) || actor.kind == reader.kind && actor.name == reader.name
        val seats = plan.seats.filter { it.retired == null && (it.session != null && it.session == reader.session ||
            it.agent != null && it.agent == PlanIdentity.agent(reader)) }
        val coverage = seats.flatMap { seat -> seat.items.flatMap { plan.under(it) } }.toSet()
        val held = plan.items.filter { item -> item.lease?.by?.let(::ours) == true || item.id in coverage || assigned(item).any(::ours) }
        val context = linkedSetOf<String>()
        val dependents = plan.items.flatMap { item -> item.after.map { it to item.id } }.groupBy({ it.first }, { it.second })
        held.forEach { item ->
            lineage(item).forEach { ancestor ->
                context += ancestor.id
                context += ancestor.after
                context += dependents[ancestor.id].orEmpty()
            }
        }
        PlanAnalysis.ready(plan).asSequence().filter { candidate ->
            (seats.isEmpty() || candidate.item.id in coverage) && assigned(candidate.item).let { it.isEmpty() || it.any(::ours) }
        }.take(8).forEach { context += it.item.id }
        return context.mapNotNull(keyed::get) + plan.items.filter { it.id !in context }
    }
}
