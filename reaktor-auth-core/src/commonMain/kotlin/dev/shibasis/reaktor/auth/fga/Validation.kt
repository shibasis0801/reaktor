package dev.shibasis.reaktor.auth.fga

data class ModelIssue(val type: String?, val relation: String?, val message: String) {
    override fun toString(): String = listOfNotNull(type?.let { t -> relation?.let { "$t#$it" } ?: t }, message).joinToString(": ")
}

object ModelRules {
    fun issues(model: AuthorizationModel): List<ModelIssue> = buildList {
        model.types.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { add(ModelIssue(it, null, "is defined twice")) }
        model.types.forEach { type ->
            if (!Names.declarable(type.name)) add(ModelIssue(type.name, null, "is not a valid type name"))
            type.relations.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { add(ModelIssue(type.name, it, "is defined twice")) }
            type.relations.forEach { relation ->
                if (!Names.relation(relation.name)) add(ModelIssue(type.name, relation.name, "is not a valid relation name"))
                addAll(references(model, type, relation.name, relation.rewrite))
            }
        }
        if (none { it.message.startsWith("refers") || it.message.startsWith("inherits") }) {
            val entry = entrypoints(model)
            model.types.forEach { type ->
                type.relations.filter { (type.name to it.name) !in entry }.forEach {
                    add(ModelIssue(type.name, it.name, "can never be granted: every path loops back without a [type list]"))
                }
            }
        }
    }

    fun tupleIssue(model: AuthorizationModel, tuple: RelationTuple): String? {
        val type = model.type(tuple.target.type) ?: return "There is no type ${tuple.target.type}"
        val relation = type.relation(tuple.relation) ?: return "${type.name} has no relation ${tuple.relation}"
        val allowed = relation.rewrite.directTypes
        if (allowed.isEmpty()) return "${type.name}#${relation.name} is computed; it cannot be granted directly"
        if (allowed.none { it.admits(tuple.subject) }) return "${type.name}#${relation.name} accepts ${allowed.joinToString(", ")}, not ${tuple.subject.kindLabel()}"
        return null
    }

    private fun Subject.kindLabel(): String = when (this) {
        is Subject.Object -> type
        is Subject.Userset -> "$type#$relation"
        is Subject.Wildcard -> "$type:*"
    }

    private fun references(model: AuthorizationModel, type: TypeDefinition, relation: String, rewrite: Rewrite): List<ModelIssue> =
        rewrite.references.flatMap { part ->
            when (part) {
                is Rewrite.Direct -> part.types.mapNotNull { ref ->
                    val target = model.type(ref.type)
                    when {
                        target == null -> ModelIssue(type.name, relation, "refers to the unknown type ${ref.type}")
                        ref.relation != null && target.relation(ref.relation) == null -> ModelIssue(type.name, relation, "refers to ${ref.type}#${ref.relation}, which is not defined")
                        else -> null
                    }
                }
                is Rewrite.Computed -> listOfNotNull(
                    if (type.relation(part.relation) == null) ModelIssue(type.name, relation, "refers to ${part.relation}, which ${type.name} does not define") else null,
                    if (part.relation == relation && rewrite == part) ModelIssue(type.name, relation, "refers only to itself") else null,
                )
                is Rewrite.From -> {
                    val tupleset = type.relation(part.tupleset)?.rewrite
                    when {
                        tupleset == null -> listOf(ModelIssue(type.name, relation, "inherits from ${part.tupleset}, which ${type.name} does not define"))
                        tupleset !is Rewrite.Direct || tupleset.types.any { it.relation != null || it.wildcard } ->
                            listOf(ModelIssue(type.name, relation, "inherits from ${part.tupleset}, which must be a plain [type list] without usersets or wildcards"))
                        tupleset.types.none { model.type(it.type)?.relation(part.relation) != null } ->
                            listOf(ModelIssue(type.name, relation, "inherits ${part.relation} from ${part.tupleset}, but none of ${tupleset.types.joinToString(", ")} defines ${part.relation}"))
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
        }

    private fun entrypoints(model: AuthorizationModel): Set<Pair<String, String>> {
        val reached = mutableSetOf<Pair<String, String>>()
        fun grantable(type: TypeDefinition, rewrite: Rewrite): Boolean = when (rewrite) {
            is Rewrite.Direct -> rewrite.types.isNotEmpty()
            is Rewrite.Computed -> (type.name to rewrite.relation) in reached
            is Rewrite.From -> (type.relation(rewrite.tupleset)?.rewrite as? Rewrite.Direct)?.types.orEmpty()
                .any { (it.type to rewrite.relation) in reached }
            is Rewrite.Union -> rewrite.children.any { grantable(type, it) }
            is Rewrite.Intersection -> rewrite.children.all { grantable(type, it) }
            is Rewrite.Exclusion -> grantable(type, rewrite.base)
        }
        do {
            val before = reached.size
            model.types.forEach { type -> type.relations.forEach { if (grantable(type, it.rewrite)) reached += type.name to it.name } }
        } while (reached.size != before)
        return reached
    }
}
