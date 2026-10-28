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

        line(0, "name: ${quoted(name)}")
        line(0, "model_file: ${quoted(modelFile)}")
        keys(0, "tuples", tuples)
        if (tests.isEmpty()) return@buildString
        line(0, "tests:")
        tests.forEach { test ->
            line(1, "- name: ${quoted(test.name)}")
            if (test.description.isNotBlank()) line(2, "description: ${quoted(test.description)}")
            if (test.tuples.isNotEmpty()) keys(2, "tuples", test.tuples)
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
