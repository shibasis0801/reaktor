package dev.shibasis.reaktor.auth.fga

class TupleIndex(tuples: Iterable<RelationTuple>) {
    val tuples: List<RelationTuple> = tuples.distinct()
    private val byTarget: Map<Pair<ObjectRef, String>, List<RelationTuple>> = this.tuples.groupBy { it.target to it.relation }

    operator fun get(target: ObjectRef, relation: String): List<RelationTuple> = byTarget[target to relation].orEmpty()

    fun objects(type: String): Set<ObjectRef> = buildSet {
        tuples.forEach { tuple ->
            if (tuple.target.type == type) add(tuple.target)
            when (val subject = tuple.subject) {
                is Subject.Object -> if (subject.type == type) add(subject.target)
                is Subject.Userset -> if (subject.type == type) add(subject.target)
                is Subject.Wildcard -> Unit
            }
        }
    }

    fun subjects(): Set<Subject> = tuples.mapTo(mutableSetOf()) { it.subject }

    operator fun plus(more: Iterable<RelationTuple>): TupleIndex = TupleIndex(tuples + more)
}

data class Explanation(
    val target: ObjectRef,
    val relation: String,
    val allowed: Boolean,
    val rule: String,
    val tuple: RelationTuple? = null,
    val children: List<Explanation> = emptyList(),
    val note: String? = null,
) {
    val reason: List<Explanation>
        get() = if (!allowed) emptyList() else listOf(this) + (children.firstOrNull { it.allowed }?.reason ?: emptyList())

    val facts: List<RelationTuple>
        get() = if (!allowed) emptyList() else listOfNotNull(tuple) + children.filter { it.allowed }.flatMap { it.facts }
}

data class CheckResult(val allowed: Boolean, val explanation: Explanation?, val error: String? = null)

sealed interface UsersetTree {
    val target: ObjectRef
    val relation: String

    data class Leaf(override val target: ObjectRef, override val relation: String, val subjects: List<Subject>) : UsersetTree
    data class Computed(override val target: ObjectRef, override val relation: String, val of: String, val tree: UsersetTree?) : UsersetTree
    data class FromParents(override val target: ObjectRef, override val relation: String, val tupleset: String, val computed: String,
        val parents: List<ObjectRef>) : UsersetTree
    data class Union(override val target: ObjectRef, override val relation: String, val children: List<UsersetTree>) : UsersetTree
    data class Intersection(override val target: ObjectRef, override val relation: String, val children: List<UsersetTree>) : UsersetTree
    data class Difference(override val target: ObjectRef, override val relation: String, val base: UsersetTree, val subtract: UsersetTree) : UsersetTree
}

class Fga(val model: AuthorizationModel, tuples: Iterable<RelationTuple>, private val known: Iterable<ObjectRef> = emptyList(), private val maxDepth: Int = 25) {
    val index = TupleIndex(tuples)

    fun check(subject: Subject, relation: String, target: ObjectRef, contextual: List<RelationTuple> = emptyList()): CheckResult {
        if (model.rewrite(target.type, relation) == null) return CheckResult(false, null, "${target.type} has no relation $relation")
        return runCatching { Resolver(if (contextual.isEmpty()) index else index + contextual).resolve(target, relation, subject, 0) }
            .fold({ CheckResult(it.allowed, it) }, { CheckResult(false, null, it.message ?: "Resolution failed") })
    }

    fun listObjects(type: String, relation: String, subject: Subject, contextual: List<RelationTuple> = emptyList()): List<ObjectRef> {
        if (model.rewrite(type, relation) == null) return emptyList()
        val tuples = if (contextual.isEmpty()) index else index + contextual
        val resolver = Resolver(tuples)
        return (tuples.objects(type) + known.filter { it.type == type }).sortedBy { it.toString() }
            .filter { runCatching { resolver.resolve(it, relation, subject, 0).allowed }.getOrDefault(false) }
    }

    fun listUsers(target: ObjectRef, relation: String, filter: TypeRef, contextual: List<RelationTuple> = emptyList()): List<Subject> {
        if (model.rewrite(target.type, relation) == null) return emptyList()
        val tuples = if (contextual.isEmpty()) index else index + contextual
        val candidates = buildSet {
            tuples.subjects().filter(filter::admits).forEach(::add)
            if (filter.relation != null) (tuples.objects(filter.type) + known.filter { it.type == filter.type })
                .forEach { add(Subject.Userset(it, filter.relation)) }
            else if (!filter.wildcard) (tuples.objects(filter.type) + known.filter { it.type == filter.type }).forEach { add(Subject.Object(it)) }
        }
        val resolver = Resolver(tuples)
        return candidates.sortedBy { it.toString() }.filter { runCatching { resolver.resolve(target, relation, it, 0).allowed }.getOrDefault(false) }
    }

    fun expand(target: ObjectRef, relation: String): UsersetTree? {
        val rewrite = model.rewrite(target.type, relation) ?: return null
        return expand(target, relation, rewrite, mutableSetOf(target to relation))
    }

    private fun expand(target: ObjectRef, relation: String, rewrite: Rewrite, seen: MutableSet<Pair<ObjectRef, String>>): UsersetTree = when (rewrite) {
        is Rewrite.Direct -> UsersetTree.Leaf(target, relation, index[target, relation].map { it.subject })
        is Rewrite.Computed -> UsersetTree.Computed(target, relation, rewrite.relation,
            model.rewrite(target.type, rewrite.relation)?.takeIf { seen.add(target to rewrite.relation) }?.let { expand(target, rewrite.relation, it, seen) })
        is Rewrite.From -> UsersetTree.FromParents(target, relation, rewrite.tupleset, rewrite.relation,
            index[target, rewrite.tupleset].mapNotNull { (it.subject as? Subject.Object)?.target })
        is Rewrite.Union -> UsersetTree.Union(target, relation, rewrite.children.map { expand(target, relation, it, seen) })
        is Rewrite.Intersection -> UsersetTree.Intersection(target, relation, rewrite.children.map { expand(target, relation, it, seen) })
        is Rewrite.Exclusion -> UsersetTree.Difference(target, relation, expand(target, relation, rewrite.base, seen), expand(target, relation, rewrite.subtract, seen))
    }

    private inner class Resolver(private val tuples: TupleIndex) {
        private val settled = HashMap<Triple<ObjectRef, String, Subject>, Explanation>()
        private val active = HashSet<Triple<ObjectRef, String, Subject>>()
        private var cut = false

        fun resolve(target: ObjectRef, relation: String, subject: Subject, depth: Int): Explanation {
            val key = Triple(target, relation, subject)
            settled[key]?.let { return it }
            if (depth > maxDepth) throw IllegalStateException("Resolution went deeper than $maxDepth steps at $target#$relation")
            if (subject is Subject.Userset && subject.target == target && subject.relation == relation)
                return Explanation(target, relation, true, "is the userset itself")
            val rewrite = model.rewrite(target.type, relation)
                ?: return Explanation(target, relation, false, "undefined", note = "${target.type} has no relation $relation")
            if (!active.add(key)) {
                cut = true
                return Explanation(target, relation, false, "cycle", note = "This path loops back to $target#$relation")
            }
            val outer = cut
            cut = false
            try {
                val result = evaluate(target, relation, subject, rewrite, depth)
                if (!cut) settled[key] = result
                return result
            } finally {
                active.remove(key)
                cut = cut || outer
            }
        }

        private fun evaluate(target: ObjectRef, relation: String, subject: Subject, rewrite: Rewrite, depth: Int): Explanation = when (rewrite) {
            is Rewrite.Direct -> direct(target, relation, subject, rewrite, depth)
            is Rewrite.Computed -> resolve(target, rewrite.relation, subject, depth + 1).let {
                Explanation(target, relation, it.allowed, rewrite.relation, children = listOf(it))
            }
            is Rewrite.From -> inherited(target, relation, subject, rewrite, depth)
            is Rewrite.Union -> {
                val children = mutableListOf<Explanation>()
                for (child in rewrite.children) {
                    val outcome = evaluate(target, relation, subject, child, depth)
                    children += outcome
                    if (outcome.allowed) break
                }
                Explanation(target, relation, children.any { it.allowed }, FgaDsl.print(rewrite), children = children)
            }
            is Rewrite.Intersection -> {
                val children = mutableListOf<Explanation>()
                for (child in rewrite.children) {
                    val outcome = evaluate(target, relation, subject, child, depth)
                    children += outcome
                    if (!outcome.allowed) break
                }
                Explanation(target, relation, children.size == rewrite.children.size && children.all { it.allowed }, FgaDsl.print(rewrite), children = children)
            }
            is Rewrite.Exclusion -> {
                val base = evaluate(target, relation, subject, rewrite.base, depth)
                val subtract = if (base.allowed) evaluate(target, relation, subject, rewrite.subtract, depth) else null
                val allowed = base.allowed && subtract?.allowed == false
                Explanation(target, relation, allowed, FgaDsl.print(rewrite), children = listOfNotNull(base, subtract?.let {
                    it.copy(allowed = !it.allowed, rule = "but not ${FgaDsl.print(rewrite.subtract)}", note = if (it.allowed) "Excluded" else "Not excluded")
                }))
            }
        }

        private fun direct(target: ObjectRef, relation: String, subject: Subject, rewrite: Rewrite.Direct, depth: Int): Explanation {
            val rule = FgaDsl.print(rewrite)
            val children = mutableListOf<Explanation>()
            val stored = tuples[target, relation].filter { tuple -> rewrite.types.any { it.admits(tuple.subject) } }
            stored.firstOrNull { it.subject == subject }?.let { return Explanation(target, relation, true, rule, it) }
            if (subject is Subject.Object) stored.firstOrNull { it.subject == Subject.Wildcard(subject.type) }
                ?.let { return Explanation(target, relation, true, rule, it, note = "Anyone of type ${subject.type}") }
            for (tuple in stored) {
                val userset = tuple.subject as? Subject.Userset ?: continue
                val inner = resolve(userset.target, userset.relation, subject, depth + 1)
                children += inner
                if (inner.allowed) return Explanation(target, relation, true, rule, tuple, listOf(inner))
            }
            return Explanation(target, relation, false, rule, children = children,
                note = if (stored.isEmpty()) "No tuple grants $target#$relation directly" else null)
        }

        private fun inherited(target: ObjectRef, relation: String, subject: Subject, rewrite: Rewrite.From, depth: Int): Explanation {
            val rule = FgaDsl.print(rewrite)
            val children = mutableListOf<Explanation>()
            val parents = tuples[target, rewrite.tupleset].mapNotNull { tuple -> (tuple.subject as? Subject.Object)?.let { tuple to it.target } }
            for ((tuple, parent) in parents) {
                if (model.rewrite(parent.type, rewrite.relation) == null) continue
                val inner = resolve(parent, rewrite.relation, subject, depth + 1)
                children += inner
                if (inner.allowed) return Explanation(target, relation, true, rule, tuple, listOf(inner))
            }
            return Explanation(target, relation, false, rule, children = children,
                note = if (parents.isEmpty()) "$target has no ${rewrite.tupleset}" else null)
        }
    }
}
