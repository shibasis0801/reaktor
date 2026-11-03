package dev.shibasis.reaktor.conductor.memory

import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.graph.ActorStateStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryStore(val database: ObjectDatabase) {
    val objects = database.store("conductor.memory")
    val events get() = database.events

    suspend fun get(id: String): MemoryFact? = objects.read<MemoryFact>(id)?.value

    suspend fun all(): List<MemoryFact> = objects.getAll<MemoryFact>().map { it.value }

    suspend fun search(query: MemoryQuery): List<MemoryFact> {
        val terms = query.text.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val facts = all()
        val superseded = facts.filter { it.status == MemoryStatus.Kept }.mapNotNull { it.supersedes }.toSet()
        return facts.filter { fact ->
            fact.scope in query.scopes && (query.kind == null || fact.kind == query.kind) &&
                (query.status == null || fact.status == query.status) && (query.link == null || query.link in fact.links) &&
                (query.status != MemoryStatus.Kept || fact.id !in superseded) &&
                terms.all { it in "${fact.title} ${fact.text} ${fact.links.joinToString()}".lowercase() }
        }.sortedWith(compareByDescending<MemoryFact> { it.importance }.thenByDescending { it.updatedAt }.thenBy { it.id }).take(query.limit)
    }

    internal fun state(scope: MemoryScope): ActorStateStore<MemoryCommand, MemoryState> = object : ActorStateStore<MemoryCommand, MemoryState> {
        private val gate = Mutex()

        override suspend fun <Out> transact(input: MemoryCommand, execute: suspend (MemoryState) -> Out): Out = gate.withLock {
            val before = all().filter { it.scope == scope }.associateBy { it.id }
            val state = MemoryState(before.toMutableMap())
            val result = execute(state)
            val changed = state.facts.values.filter { it != before[it.id] }
            check(changed.size <= 1 && state.facts.keys.containsAll(before.keys))
            changed.singleOrNull()?.let { fact ->
                check(database.compareAndSet(objects.storeName, fact.id, before[fact.id], fact, MemoryFact.serializer())) {
                    "Memory changed in another host; read its current revision and retry"
                }
            }
            result
        }
    }
}

internal class MemoryState(val facts: MutableMap<String, MemoryFact>)
