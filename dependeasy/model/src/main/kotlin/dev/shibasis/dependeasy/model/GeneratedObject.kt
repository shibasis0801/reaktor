package dev.shibasis.dependeasy.model

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class GeneratedObject(private val namespace: String, private val content: Fields.() -> Unit) {
    data class Text(val path: String, val enabled: BuildOption<Boolean>) : java.io.Serializable
    data class Spec(val namespace: String, val objectName: String,
        val strings: Map<String, BuildOption<String>>, val flags: Map<String, BuildOption<Boolean>> = emptyMap(),
        val revisions: Set<String> = emptySet(), val texts: Map<String, Text> = emptyMap()) : java.io.Serializable

    class Fields internal constructor() {
        internal val strings = linkedMapOf<String, BuildOption<String>>()
        internal val flags = linkedMapOf<String, BuildOption<Boolean>>()
        internal val revisions = linkedSetOf<String>()
        internal val texts = linkedMapOf<String, Text>()
        private val names = mutableSetOf<String>()

        private fun <T> field(value: T, record: (String, T) -> Unit) =
            PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, T>> { _, property ->
                require(names.add(property.name)) { "A generated field is declared once" }
                record(property.name, value)
                ReadOnlyProperty { _, _ -> value }
            }

        fun string(option: BuildOption<String>) = field(option) { name, value -> strings[name] = value }
        fun flag(option: BuildOption<Boolean>) = field(option) { name, value -> flags[name] = value }
        fun revision() = field(Unit) { name, _ -> revisions.add(name) }
        fun text(path: String, enabled: BuildOption<Boolean>) = field(Text(path, enabled)) { name, value ->
            require(path.isNotBlank()) { "Generated text needs its source path" }
            texts[name] = value
        }
    }

    operator fun provideDelegate(owner: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, Spec> {
        require(namespace.isNotBlank()) { "Generated Kotlin needs its namespace" }
        val fields = Fields().apply(content)
        val spec = Spec(namespace, property.name, fields.strings.toMap(), fields.flags.toMap(), fields.revisions.toSet(), fields.texts.toMap())
        return ReadOnlyProperty { _, _ -> spec }
    }
}
