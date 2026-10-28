package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.ObjectRef
import dev.shibasis.reaktor.auth.fga.RelationTuple
import kotlinx.serialization.Serializable

@Serializable
data class CodeFacts(
    val schema: Int = 1,
    val app: String,
    val environment: String,
    val sourceRevision: String,
    val objects: List<String>,
    val structure: List<String>,
    val declared: List<String>,
) {
    val known: List<ObjectRef> get() = objects.map { requireNotNull(ObjectRef.parse(it)) { "Unreadable code object $it" } }

    val tuples: List<RelationTuple> get() = (structure + declared).map { requireNotNull(RelationTuple.parse(it)) { "Unreadable code fact $it" } }

    companion object {
        fun of(app: String, environment: String, sourceRevision: String, resources: CodeResources): CodeFacts =
            CodeFacts(app = CodeAccess.slug(app), environment = environment, sourceRevision = sourceRevision,
                objects = resources.objects.map(ObjectRef::toString).distinct(),
                structure = resources.structure.map(RelationTuple::toString).distinct(),
                declared = resources.declared.map(RelationTuple::toString))
    }
}
