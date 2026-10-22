package dev.shibasis.reaktor.auth.fga

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class ObjectRef(val type: String, val id: String) {
    override fun toString(): String = "$type:$id"

    companion object {
        fun parse(text: String): ObjectRef? {
            val type = text.substringBefore(':', "")
            val id = text.substringAfter(':', "")
            return ObjectRef(type, id).takeIf { Names.type(type) && id.isNotEmpty() && id != "*" && id.none { it == '#' || it == '@' || it.isWhitespace() } }
        }
    }
}

sealed interface Subject {
    val type: String

    data class Object(val target: ObjectRef) : Subject {
        override val type: String get() = target.type
        override fun toString(): String = target.toString()
    }

    data class Userset(val target: ObjectRef, val relation: String) : Subject {
        override val type: String get() = target.type
        override fun toString(): String = "$target#$relation"
    }

    data class Wildcard(override val type: String) : Subject {
        override fun toString(): String = "$type:*"
    }

    companion object {
        fun parse(text: String): Subject? {
            val trimmed = text.trim()
            if (trimmed.endsWith(":*")) return trimmed.removeSuffix(":*").takeIf(Names::type)?.let(::Wildcard)
            val target = ObjectRef.parse(trimmed.substringBefore('#')) ?: return null
            if ('#' !in trimmed) return Object(target)
            return trimmed.substringAfter('#').takeIf(Names::relation)?.let { Userset(target, it) }
        }

        fun of(type: String, id: String): Subject = Object(ObjectRef(type, id))
    }
}

data class RelationTuple(val target: ObjectRef, val relation: String, val subject: Subject) {
    override fun toString(): String = "$target#$relation@$subject"

    val key: TupleKey get() = TupleKey(subject.toString(), relation, target.toString())

    companion object {
        fun parse(text: String): RelationTuple? {
            val at = text.indexOf('@').takeIf { it > 0 } ?: return null
            val left = text.substring(0, at).trim()
            val target = ObjectRef.parse(left.substringBefore('#')) ?: return null
            val relation = left.substringAfter('#', "").takeIf(Names::relation) ?: return null
            return Subject.parse(text.substring(at + 1))?.let { RelationTuple(target, relation, it) }
        }

        fun of(key: TupleKey): RelationTuple? {
            val target = ObjectRef.parse(key.target.trim()) ?: return null
            val subject = Subject.parse(key.user) ?: return null
            return key.relation.trim().takeIf(Names::relation)?.let { RelationTuple(target, it, subject) }
        }
    }
}

@Serializable
data class TupleKey(val user: String, val relation: String, @SerialName("object") val target: String)

data class TypeRef(val type: String, val relation: String? = null, val wildcard: Boolean = false) {
    override fun toString(): String = when {
        wildcard -> "$type:*"
        relation != null -> "$type#$relation"
        else -> type
    }

    fun admits(subject: Subject): Boolean = when (subject) {
        is Subject.Object -> !wildcard && relation == null && subject.type == type
        is Subject.Userset -> !wildcard && relation == subject.relation && subject.type == type
        is Subject.Wildcard -> wildcard && subject.type == type
    }
}

sealed interface Rewrite {
    data class Direct(val types: List<TypeRef>) : Rewrite
    data class Computed(val relation: String) : Rewrite
    data class From(val relation: String, val tupleset: String) : Rewrite
    data class Union(val children: List<Rewrite>) : Rewrite
    data class Intersection(val children: List<Rewrite>) : Rewrite
    data class Exclusion(val base: Rewrite, val subtract: Rewrite) : Rewrite

    val directTypes: List<TypeRef>
        get() = when (this) {
            is Direct -> types
            is Computed, is From -> emptyList()
            is Union -> children.flatMap { it.directTypes }
            is Intersection -> children.flatMap { it.directTypes }
            is Exclusion -> base.directTypes
        }.distinct()

    val references: List<Rewrite>
        get() = when (this) {
            is Union -> children.flatMap { it.references }
            is Intersection -> children.flatMap { it.references }
            is Exclusion -> base.references + subtract.references
            else -> listOf(this)
        }

    fun or(other: Rewrite): Rewrite = when {
        this == other -> this
        this is Union && other is Union -> Union((children + other.children).distinct())
        this is Union -> if (other in children) this else Union(children + other)
        other is Union -> if (this in other.children) other else Union(listOf(this) + other.children)
        else -> Union(listOf(this, other))
    }

    fun without(part: Rewrite): Rewrite? = when {
        this == part -> null
        this is Union -> children.mapNotNull { it.without(part) }.let { rest -> rest.singleOrNull() ?: rest.takeIf { it.isNotEmpty() }?.let(::Union) }
        else -> this
    }

    fun withDirect(type: TypeRef): Rewrite = when (this) {
        is Direct -> if (type in types) this else Direct(types + type)
        is Union -> children.indexOfFirst { it is Direct }.let { at ->
            if (at < 0) Union(listOf(Direct(listOf(type))) + children)
            else Union(children.mapIndexed { index, child -> if (index == at) child.withDirect(type) else child })
        }
        is Exclusion -> copy(base = base.withDirect(type))
        else -> Union(listOf(Direct(listOf(type)), this))
    }
}

data class RelationDefinition(val name: String, val rewrite: Rewrite)

data class TypeDefinition(val name: String, val relations: List<RelationDefinition> = emptyList()) {
    fun relation(name: String): RelationDefinition? = relations.firstOrNull { it.name == name }
}

data class AuthorizationModel(val types: List<TypeDefinition>, val schema: String = "1.1") {
    private val byName: Map<String, TypeDefinition> = types.associateBy { it.name }

    fun type(name: String): TypeDefinition? = byName[name]

    fun rewrite(type: String, relation: String): Rewrite? = byName[type]?.relation(relation)?.rewrite

    fun directTypes(type: String, relation: String): List<TypeRef> = rewrite(type, relation)?.directTypes.orEmpty()

    fun withType(type: TypeDefinition): AuthorizationModel =
        copy(types = if (type.name in byName) types.map { if (it.name == type.name) type else it } else types + type)

    fun withRelation(type: String, relation: RelationDefinition): AuthorizationModel {
        val owner = byName[type] ?: TypeDefinition(type)
        val relations = if (owner.relation(relation.name) == null) owner.relations + relation
            else owner.relations.map { if (it.name == relation.name) relation else it }
        return withType(owner.copy(relations = relations))
    }

    fun withoutRelation(type: String, relation: String): AuthorizationModel =
        byName[type]?.let { owner -> withType(owner.copy(relations = owner.relations.filterNot { it.name == relation })) } ?: this

    fun withoutType(type: String): AuthorizationModel = copy(types = types.filterNot { it.name == type })
}

internal object Names {
    private val reserved = setOf("self", "this", "define", "relations", "type", "model", "schema", "or", "and", "but", "not", "from", "with")

    fun type(name: String): Boolean = name.isNotEmpty() && name.length <= 254 && name.all(::allowed)

    fun relation(name: String): Boolean = name.isNotEmpty() && name.length <= 50 && name.all(::allowed) && name !in reserved

    fun declarable(name: String): Boolean = type(name) && name !in reserved

    private fun allowed(char: Char) = char.isLetterOrDigit() || char == '_' || char == '-' || char == '.' || char == '/'
}
