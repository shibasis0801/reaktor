package dev.shibasis.reaktor.auth.fga

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FgaStore(
    val name: String = "",
    @SerialName("model_file") val modelFile: String? = null,
    val tuples: List<TupleKey> = emptyList(),
    val tests: List<FgaTest> = emptyList(),
    val modes: Map<String, AccessMode> = emptyMap(),
) {
    fun relationTuples(): List<RelationTuple> = tuples.mapNotNull(RelationTuple::of)

    fun invalidTuples(): List<TupleKey> = tuples.filter { RelationTuple.of(it) == null }

    fun with(tuple: RelationTuple): FgaStore = if (tuple.key in tuples) this else copy(tuples = tuples + tuple.key)

    fun without(tuple: RelationTuple): FgaStore = copy(tuples = tuples - tuple.key)

    fun mode(target: ObjectRef): AccessMode? = modes[target.toString()] ?: modes[EveryObject]

    fun withMode(target: String, mode: AccessMode?): FgaStore =
        copy(modes = if (mode == null) modes - target else modes + (target to mode))

    fun encode(): String = StoreJson.encodeToString(serializer(), this) + "\n"

    companion object {
        const val EveryObject = "*"

        fun decode(text: String): FgaStore = StoreJson.decodeFromString(serializer(), text)
    }
}

@Serializable
data class FgaTest(
    val name: String,
    val description: String = "",
    val tuples: List<TupleKey> = emptyList(),
    val check: List<CheckAssertion> = emptyList(),
    @SerialName("list_objects") val listObjects: List<ListObjectsAssertion> = emptyList(),
)

@Serializable
data class CheckAssertion(val user: String, @SerialName("object") val target: String, val assertions: Map<String, Boolean>)

@Serializable
data class ListObjectsAssertion(val user: String, val type: String, val assertions: Map<String, List<String>>)

data class AssertionOutcome(
    val test: String,
    val question: String,
    val expected: String,
    val actual: String,
    val passed: Boolean,
    val explanation: Explanation? = null,
)

object FgaTests {
    fun run(model: AuthorizationModel, store: FgaStore, known: Iterable<ObjectRef> = emptyList(), base: List<RelationTuple> = store.relationTuples()): List<AssertionOutcome> =
        store.tests.flatMap { test ->
            val local = test.tuples.mapNotNull(RelationTuple::of)
            val fga = Fga(model, base + local, known)
            val broken = test.tuples.filter { RelationTuple.of(it) == null }.map {
                AssertionOutcome(test.name, "tuple ${it.target}#${it.relation}@${it.user}", "a valid tuple", "unreadable", false)
            }
            broken + test.check.flatMap { assertion ->
                val subject = Subject.parse(assertion.user)
                val target = ObjectRef.parse(assertion.target)
                assertion.assertions.entries.sortedBy { it.key }.map { (relation, expected) ->
                    val question = "${assertion.user} ${relation} ${assertion.target}"
                    if (subject == null || target == null) AssertionOutcome(test.name, question, expected.toString(), "unreadable user or object", false)
                    else {
                        val result = fga.check(subject, relation, target)
                        AssertionOutcome(test.name, question, expected.toString(), result.error ?: result.allowed.toString(),
                            result.error == null && result.allowed == expected, result.explanation)
                    }
                }
            } + test.listObjects.flatMap { assertion ->
                val subject = Subject.parse(assertion.user)
                assertion.assertions.entries.sortedBy { it.key }.map { (relation, expected) ->
                    val question = "${assertion.user} ${relation} every ${assertion.type}"
                    val actual = subject?.let { fga.listObjects(assertion.type, relation, it).map(ObjectRef::toString).sorted() }
                    AssertionOutcome(test.name, question, expected.sorted().joinToString(", ").ifEmpty { "nothing" },
                        actual?.joinToString(", ")?.ifEmpty { "nothing" } ?: "unreadable user", actual == expected.sorted())
                }
            }
        }
}

private val StoreJson = Json {
    prettyPrint = true
    encodeDefaults = false
    ignoreUnknownKeys = true
}
