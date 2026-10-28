package dev.shibasis.reaktor.auth.fga

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

sealed interface DirectType {
    val ref: TypeRef
}

sealed interface FgaExpr

private class Written(val rewrite: Rewrite) : FgaExpr

private class Wildcard(type: FgaType) : DirectType {
    override val ref: TypeRef = TypeRef(type.name, wildcard = true)
}

class FgaRelation internal constructor(val type: FgaType, val name: String) : FgaExpr, DirectType {
    override val ref: TypeRef get() = TypeRef(type.name, name)

    operator fun invoke(id: String): Subject = Subject.Userset(type(id), name)

    fun tuple(id: String, subject: Subject): RelationTuple = RelationTuple(type(id), name, subject)

    override fun toString(): String = ref.toString()
}

abstract class FgaType(val name: String) : DirectType {
    private val defined = mutableListOf<Pair<FgaRelation, RewriteScope.() -> FgaExpr>>()

    override val ref: TypeRef get() = TypeRef(name)

    val wildcard: DirectType get() = Wildcard(this)

    val relations: List<FgaRelation> get() = defined.map { it.first }

    operator fun invoke(id: String): ObjectRef = ObjectRef(name, id)

    fun subject(id: String): Subject = Subject.Object(this(id))

    protected fun relation(name: String? = null, rewrite: RewriteScope.() -> FgaExpr) =
        PropertyDelegateProvider<FgaType, ReadOnlyProperty<FgaType, FgaRelation>> { owner, property ->
            val relation = FgaRelation(owner, name ?: FgaNames.relation(property.name))
            defined += relation to rewrite
            ReadOnlyProperty { _, _ -> relation }
        }

    internal fun definition(): TypeDefinition =
        TypeDefinition(name, defined.map { (relation, rewrite) -> RelationDefinition(relation.name, RewriteScope(this).let { it.resolve(it.rewrite()) }) })

    override fun toString(): String = name
}

class RewriteScope internal constructor(private val defining: FgaType) {
    fun direct(vararg types: DirectType): FgaExpr = direct(types.asList())

    fun direct(types: List<DirectType>): FgaExpr = Written(Rewrite.Direct(types.map { it.ref }.distinct()))

    infix fun FgaExpr.or(other: FgaExpr): FgaExpr = Written(resolve(this).or(resolve(other)))

    infix fun FgaExpr.and(other: FgaExpr): FgaExpr = Written(Rewrite.Intersection(conjuncts(resolve(this)) + conjuncts(resolve(other))))

    infix fun FgaExpr.butNot(other: FgaExpr): FgaExpr = Written(Rewrite.Exclusion(resolve(this), resolve(other)))

    infix fun FgaRelation.from(tupleset: FgaRelation): FgaExpr = name from tupleset

    infix fun String.from(tupleset: FgaRelation): FgaExpr {
        require(tupleset.type === defining) { "$tupleset is not a relation of $defining, so $defining cannot inherit $this from it" }
        return Written(Rewrite.From(this, tupleset.name))
    }

    internal fun resolve(expr: FgaExpr): Rewrite = when (expr) {
        is Written -> expr.rewrite
        is FgaRelation -> {
            require(expr.type === defining) { "$expr belongs to ${expr.type}; $defining can only compute its own relations. Use ${expr.name} from a relation of $defining" }
            Rewrite.Computed(expr.name)
        }
    }

    private fun conjuncts(rewrite: Rewrite): List<Rewrite> = if (rewrite is Rewrite.Intersection) rewrite.children else listOf(rewrite)
}

abstract class FgaSchema(val schema: String = "1.1") {
    abstract val types: List<FgaType>

    val model: AuthorizationModel by lazy { AuthorizationModel(types.map(FgaType::definition), schema) }

    val issues: List<ModelIssue> get() = ModelRules.issues(model)

    fun dsl(): String = FgaDsl.print(model)

    fun json(): String = FgaJson.encode(model)
}

fun Fga.check(subject: Subject, relation: FgaRelation, id: String, contextual: List<RelationTuple> = emptyList()): CheckResult =
    check(subject, relation.name, relation.type(id), contextual)

internal object FgaNames {
    fun relation(property: String): String = buildString {
        property.forEachIndexed { index, char ->
            if (char.isUpperCase() && index > 0) append('_')
            append(char.lowercaseChar())
        }
    }

    fun property(relation: String): String {
        val words = relation.split('_', '-', '.', '/').filter(String::isNotEmpty)
        val joined = words.mapIndexed { index, word -> if (index == 0) word.lowercase() else word.replaceFirstChar(Char::uppercaseChar) }.joinToString("")
        return if (joined.firstOrNull()?.isLetter() == true) joined else "r$joined"
    }

    fun type(name: String): String = name.split('_', '-', '.', '/').filter(String::isNotEmpty).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        .let { if (it.firstOrNull()?.isLetter() == true) it else "T$it" }
}
