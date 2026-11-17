package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
private data class PlanReadCursor(val thread: String, val revision: Long, val reader: String, val frame: String? = null,
                                  val offset: Int = 0, val fragment: Int = 0, val scoped: Boolean = false,
                                  val metadataOffset: Int? = null, val metadataDone: Boolean = false)

object PlanReadPage {
    const val DefaultBytes = 65_536
    const val MaximumBytes = 262_144
    private val json = Json { encodeDefaults = true }

    fun thread(continuation: String): String = json.decodeFromString(PlanReadCursor.serializer(), continuation).thread

    fun answer(plan: WorkPlan, reader: BoardActor, extra: JsonObject = JsonObject(emptyMap()), frame: String? = null,
               continuation: String? = null, budgetBytes: Int? = null, scoped: Boolean = false,
               projection: ((WorkPlan) -> JsonObject)? = null): JsonObject {
        val budget = (budgetBytes ?: plan.limits.readBytes).coerceAtMost(minOf(plan.limits.maxReadBytes, MaximumBytes))
        require(budget >= 4096) { "Use an answer budget of at least 4096 bytes" }
        val news = (extra["news"] as? JsonArray)?.sortedBy { it.jsonObject["revision"]?.jsonPrimitive?.longOrNull ?: 0 }
        val shownNews = news?.let { changes ->
            var bytes = 2
            changes.takeWhile { change ->
                val size = change.toString().encodeToByteArray().size + 1
                (bytes == 2 || bytes + size <= budget / 4).also { if (it) bytes += size }
            }
        }
        val identity = PlanPeople.ref(reader) + (reader.session ?: "")
        val cursor = continuation?.let { json.decodeFromString(PlanReadCursor.serializer(), it) }
            ?: PlanReadCursor(plan.thread, plan.revision, identity, frame, scoped = scoped)
        if (cursor.thread != plan.thread || cursor.revision != plan.revision || cursor.reader != identity || cursor.frame != frame || cursor.scoped != scoped)
            return buildJsonObject { put("restart", true); put("reason", "The plan changed or the selection changed; restart this read"); put("revision", plan.revision) }
        val order = order(plan, reader, frame, scoped)
        val orderedIds = order.mapTo(HashSet()) { it.id }
        require(cursor.offset in 0..order.size && cursor.fragment >= 0 && (cursor.metadataOffset ?: 0) >= 0) { "Invalid plan continuation" }
        val remaining = order.drop(cursor.offset)
        val history = (extra["plan"] as? JsonObject)?.get("changes") as? JsonArray
        val historyInMetadata = history != null && history.toString().encodeToByteArray().size > budget / 4
        fun token(offset: Int, fragment: Int = 0, metadataOffset: Int? = null, metadataDone: Boolean = cursor.metadataDone) =
            json.encodeToString(PlanReadCursor.serializer(), cursor.copy(offset = offset, fragment = fragment,
                metadataOffset = metadataOffset, metadataDone = metadataDone))
        fun page(items: List<PlanItem>): WorkPlan {
            val seats = items.mapNotNullTo(HashSet()) { it.seat }
            val ids = items.mapTo(HashSet()) { it.id }
            return plan.copy(items = items, seats = plan.seats.filter { it.id in seats || it.items.any(ids::contains) },
                changes = if (!historyInMetadata && !history.isNullOrEmpty() && cursor.offset == 0 && !cursor.metadataDone) plan.changes else emptyList(),
                order = plan.order.filter { it in ids }, documentText = null)
        }
        fun metadata(items: List<PlanItem>, fragmentId: String? = null): JsonObject = buildJsonObject {
            val ids = items.mapTo(HashSet()) { it.id }.also { fragmentId?.let(it::add) }
            val claims = (extra["claims"] as? JsonObject)?.get("claims") as? JsonObject
            if (claims != null) put("claims", buildJsonObject { put("claims", JsonObject(claims.filterKeys { it in ids })) })
            (extra["ready"] as? JsonArray)?.let { ready -> put("ready", JsonArray(ready.filter {
                !scoped || it.jsonObject["item"]?.jsonObject?.get("id")?.jsonPrimitive?.content in orderedIds
            }.map(::compactCandidate))) }
            if (shownNews != null) {
                put("news", JsonArray(shownNews))
                put("newsTruncated", shownNews.size < news!!.size)
                put("newsSince", shownNews.lastOrNull()?.jsonObject?.get("revision") ?: JsonPrimitive(plan.revision))
            }
            if (historyInMetadata) put("history", history!!)
            extra.filterKeys { it !in setOf("plan", "claims", "ready", "news", "newsTruncated", "newsSince") }.forEach { (key, value) -> put(key, value) }
            projection?.invoke(page(items))?.forEach { (key, value) -> put(key, value) }
        }
        fun envelope(items: List<PlanItem>, next: String?, fragment: JsonObject? = null,
                     fields: JsonObject = if (cursor.metadataDone) JsonObject(emptyMap()) else metadata(items, fragment?.get("item")?.jsonPrimitive?.content),
                     metadataFragment: JsonObject? = null): JsonObject = buildJsonObject {
                put("plan", json.encodeToJsonElement(WorkPlan.serializer(), page(items)))
                put("projection", "agent-page")
                put("complete", cursor.offset == 0 && next == null && fragment == null && metadataFragment == null)
                put("total", order.size)
                put("offset", cursor.offset)
                if (plan.documentText != null || plan.documentPath != null) put("documentAvailable", true)
                fields.forEach { (key, value) -> put(key, value) }
                if (next != null) put("continuation", next)
                if (fragment != null) put("fragment", fragment)
                if (metadataFragment != null) put("metadataFragment", metadataFragment)
            }
        fun JsonObject.fits() = toString().encodeToByteArray().size <= budget
        fun boundary(text: String, end: Int): Int = if (end > 0 && end < text.length &&
            text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end - 1 else end
        fun metadataPage(): JsonObject {
            val encoded = metadata(order).toString()
            val start = cursor.metadataOffset ?: 0
            require(start < encoded.length) { "Invalid metadata continuation" }
            fun part(rawEnd: Int): JsonObject {
                val end = boundary(encoded, rawEnd)
                val next = if (end < encoded.length) token(0, metadataOffset = end) else
                    if (order.isNotEmpty()) token(0, metadataDone = true) else null
                return envelope(emptyList(), next, fields = JsonObject(emptyMap()), metadataFragment = buildJsonObject {
                    put("encoding", "json"); put("offset", start); put("text", encoded.substring(start, end)); put("totalChars", encoded.length)
                })
            }
            var low = start
            var high = encoded.length
            while (low < high) {
                val end = (low + high + 1) / 2
                if (part(end).fits()) low = end else high = end - 1
            }
            require(boundary(encoded, low) > start) { "The plan metadata needs a larger answer budget" }
            return part(low)
        }
        if (cursor.metadataOffset != null || !cursor.metadataDone && !envelope(emptyList(), token(cursor.offset)).fits()) return metadataPage()
        var low = 0
        var high = remaining.size
        if (cursor.fragment == 0) {
            while (low < high) {
                val count = (low + high + 1) / 2
                val next = (cursor.offset + count).takeIf { it < order.size }?.let { token(it) }
                if (envelope(remaining.take(count), next).fits()) low = count else high = count - 1
            }
            if (low > 0 || remaining.isEmpty()) return envelope(remaining.take(low),
                (cursor.offset + low).takeIf { it < order.size }?.let { token(it) }).also {
                require(it.fits()) { "The plan metadata needs a larger answer budget" }
            }
        }
        val item = remaining.first()
        val encoded = json.encodeToString(PlanItem.serializer(), item)
        require(cursor.fragment < encoded.length) { "Invalid item continuation" }
        fun part(rawEnd: Int): JsonObject {
            val end = boundary(encoded, rawEnd)
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
            if (part(end).fits()) low = end else high = end - 1
        }
        if (boundary(encoded, low) <= cursor.fragment && !cursor.metadataDone) return metadataPage()
        require(boundary(encoded, low) > cursor.fragment) { "The plan metadata needs a larger answer budget" }
        return part(low)
    }

    fun compactCandidate(value: JsonElement): JsonElement {
        val candidate = value as? JsonObject ?: return value
        val item = candidate["item"] as? JsonObject ?: return value
        return JsonObject(candidate + mapOf("item" to JsonObject(item.filterKeys { it in setOf("id", "title", "status", "kind", "priority", "parent", "revision") }),
            "itemPartial" to JsonPrimitive(true), "itemFrame" to (item["id"] ?: JsonNull)))
    }

    private fun order(plan: WorkPlan, reader: BoardActor, frame: String?, scoped: Boolean): List<PlanItem> {
        if (frame != null) {
            require(plan.item(frame) != null) { "$frame is not a frame in this plan" }
            val ids = plan.under(frame)
            return plan.items.filter { it.id in ids }
        }
        val keyed = plan.items.associateBy { it.id }
        fun lineage(item: PlanItem) = generateSequence(item) { it.parent?.let(keyed::get) }.take(keyed.size)
        fun assigned(item: PlanItem) = PlanPeople.assignee(item, keyed)
        fun assignedToUs(item: PlanItem) = assigned(item).let { it == reader.name || it == PlanPeople.statedName(reader) }
        fun ours(actor: BoardActor) = actor.same(reader) || actor.kind == reader.kind && actor.name == reader.name
        val seats = plan.seats.filter { it.retired == null && (it.session != null && it.session == reader.session ||
            it.agent != null && it.agent == PlanIdentity.agent(reader)) }
        val coverage = seats.flatMap { seat -> seat.items.flatMap { plan.under(it) } }.toSet()
        val held = plan.items.filter { item -> item.lease?.by?.let(::ours) == true || item.id in coverage || assigned(item) != null && assignedToUs(item) }
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
            (seats.isEmpty() || candidate.item.id in coverage) && (assigned(candidate.item) == null || assignedToUs(candidate.item))
        }.take(8).forEach { context += it.item.id }
        val pending = ArrayDeque(context)
        while (pending.isNotEmpty()) {
            val item = keyed[pending.removeFirst()] ?: continue
            (listOfNotNull(item.parent) + item.after).forEach { if (context.add(it)) pending.addLast(it) }
        }
        return context.mapNotNull(keyed::get) + if (scoped) emptyList() else plan.items.filter { it.id !in context }
    }
}
