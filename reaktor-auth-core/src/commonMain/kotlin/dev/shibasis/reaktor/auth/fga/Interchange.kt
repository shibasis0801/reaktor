package dev.shibasis.reaktor.auth.fga

import kotlinx.serialization.json.JsonPrimitive

object FgaInterchange {
    fun store(name: String, modelFile: String, tuples: List<TupleKey>, tests: List<FgaTest> = emptyList()): String = buildString {
        fun line(depth: Int, text: String) {
            append("  ".repeat(depth)).append(text).append('\n')
        }

        fun quoted(text: String) = JsonPrimitive(text).toString()

        fun keys(depth: Int, label: String, keys: List<TupleKey>) {
            if (keys.isEmpty()) {
                line(depth, "$label: []")
                return
            }
            line(depth, "$label:")
            keys.forEach { key ->
                line(depth + 1, "- user: ${quoted(key.user)}")
                line(depth + 2, "relation: ${quoted(key.relation)}")
                line(depth + 2, "object: ${quoted(key.target)}")
            }
        }

        val stored = tuples.map(FgaIds::encode).distinct()
        val inStore = stored.toSet()
        line(0, "name: ${quoted(name)}")
        line(0, "model_file: ${quoted(modelFile)}")
        keys(0, "tuples", stored)
        if (tests.isEmpty()) return@buildString
        line(0, "tests:")
        tests.map(FgaIds::encode).forEach { test ->
            val added = test.tuples.distinct() - inStore
            line(1, "- name: ${quoted(test.name)}")
            if (test.description.isNotBlank()) line(2, "description: ${quoted(test.description)}")
            if (added.isNotEmpty()) keys(2, "tuples", added)
            if (test.check.isNotEmpty()) {
                line(2, "check:")
                test.check.forEach { check ->
                    line(3, "- user: ${quoted(check.user)}")
                    line(4, "object: ${quoted(check.target)}")
                    line(4, "assertions:")
                    check.assertions.entries.sortedBy { it.key }.forEach { (relation, expected) -> line(5, "$relation: $expected") }
                }
            }
            if (test.listObjects.isNotEmpty()) {
                line(2, "list_objects:")
                test.listObjects.forEach { list ->
                    line(3, "- user: ${quoted(list.user)}")
                    line(4, "type: ${quoted(list.type)}")
                    line(4, "assertions:")
                    list.assertions.entries.sortedBy { it.key }.forEach { (relation, objects) ->
                        if (objects.isEmpty()) line(5, "$relation: []")
                        else {
                            line(5, "$relation:")
                            objects.sorted().forEach { line(6, "- ${quoted(it)}") }
                        }
                    }
                }
            }
        }
    }

    fun extensions(store: FgaStore): String = FgaStore(store.name, modes = store.modes, app = store.app, roles = store.roles).encode()
}

object FgaIds {
    fun encode(reference: String): String = reference.withId { it.replace("%", "%25").replace(":", "%3A") }

    fun decode(reference: String): String = reference.withId { it.replace("%3A", ":").replace("%25", "%") }

    fun encode(key: TupleKey): TupleKey = TupleKey(encode(key.user), key.relation, encode(key.target))

    fun encode(test: FgaTest): FgaTest = test.copy(
        tuples = test.tuples.map(::encode),
        check = test.check.map { it.copy(user = encode(it.user), target = encode(it.target)) },
        listObjects = test.listObjects.map { list -> list.copy(user = encode(list.user), assertions = list.assertions.mapValues { (_, objects) -> objects.map(::encode) }) },
    )

    private fun String.withId(transform: (String) -> String): String {
        val type = substringBefore(':', "")
        val rest = substringAfter(':', "")
        if (type.isEmpty() || rest.isEmpty()) return this
        val id = transform(rest.substringBefore('#'))
        return if ('#' in rest) "$type:$id#${rest.substringAfter('#')}" else "$type:$id"
    }
}
