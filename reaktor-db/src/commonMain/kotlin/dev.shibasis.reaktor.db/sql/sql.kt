package dev.shibasis.reaktor.db.sql

import kotlin.js.JsExport
import kotlin.js.JsName

@JsExport
interface SqlType<T> {
    val sqlString: String
}

@JsExport
object IntegerType : SqlType<Int> { override val sqlString = "INTEGER" }
@JsExport
object TextType : SqlType<String> { override val sqlString = "TEXT" }
@JsExport
object BooleanType : SqlType<Boolean> { override val sqlString = "INTEGER" }
@JsExport
object DoubleType : SqlType<Double> { override val sqlString = "REAL" }
@JsExport
object BlobType : SqlType<ByteArray> { override val sqlString = "BLOB" }

@JsExport
data class ColumnDefinition(
    val isPrimaryKey: Boolean = false,
    val isAutoIncrement: Boolean = false,
    val isNullable: Boolean = true,
    val defaultValue: String? = null
)

@JsExport
abstract class Table(val tableName: String) {
    private val _columns = mutableListOf<Column<*>>()
    val columns: List<Column<*>> get() = _columns

    protected fun integer(name: String, primaryKey: Boolean = false, autoIncrement: Boolean = false, nullable: Boolean = true, default: Int? = null): Column<Int> =
        Column(name, IntegerType, this, ColumnDefinition(primaryKey, autoIncrement, nullable, default?.toString())).also { _columns.add(it) }

    protected fun text(name: String, primaryKey: Boolean = false, nullable: Boolean = true, default: String? = null): Column<String> =
        Column(name, TextType, this, ColumnDefinition(primaryKey, false, nullable, if (default != null) "'$default'" else null)).also { _columns.add(it) }

    protected fun bool(name: String, nullable: Boolean = true, default: Boolean? = null): Column<Boolean> =
        Column(name, BooleanType, this, ColumnDefinition(false, false, nullable, if (default != null) (if (default) "1" else "0") else null)).also { _columns.add(it) }

    protected fun double(name: String, nullable: Boolean = true, default: Double? = null): Column<Double> =
        Column(name, DoubleType, this, ColumnDefinition(false, false, nullable, default?.toString())).also { _columns.add(it) }

    protected fun blob(name: String, nullable: Boolean = true): Column<ByteArray> =
        Column(name, BlobType, this, ColumnDefinition(false, false, nullable, null)).also { _columns.add(it) }
}

@JsExport
data class Column<T>(
    val name: String,
    val type: SqlType<T>,
    val table: Table,
    val definition: ColumnDefinition
)

@JsExport
sealed class Expression {
    data class Eq<T>(val column: Column<T>, val value: T) : Expression()
    data class Neq<T>(val column: Column<T>, val value: T) : Expression()
    data class Gt<T>(val column: Column<T>, val value: T) : Expression()
    data class Lt<T>(val column: Column<T>, val value: T) : Expression()
    data class Gte<T>(val column: Column<T>, val value: T) : Expression()
    data class Lte<T>(val column: Column<T>, val value: T) : Expression()
    data class Like(val column: Column<String>, val value: String) : Expression()
    data class And(val left: Expression, val right: Expression) : Expression()
    data class Or(val left: Expression, val right: Expression) : Expression()
    object Empty : Expression()
}

infix fun <T> Column<T>.eq(value: T): Expression = Expression.Eq(this, value)
infix fun <T> Column<T>.neq(value: T): Expression = Expression.Neq(this, value)
infix fun <T> Column<T>.gt(value: T): Expression = Expression.Gt(this, value)
infix fun <T> Column<T>.lt(value: T): Expression = Expression.Lt(this, value)
infix fun <T> Column<T>.gte(value: T): Expression = Expression.Gte(this, value)
infix fun <T> Column<T>.lte(value: T): Expression = Expression.Lte(this, value)
infix fun Column<String>.like(value: String): Expression = Expression.Like(this, value)
infix fun Expression.and(other: Expression): Expression = Expression.And(this, other)
infix fun Expression.or(other: Expression): Expression = Expression.Or(this, other)

@JsExport
interface Statement {
    fun renderSql(): String
    fun renderArgs(): Array<Any?>
}

@JsExport
data class RenderResult(
    val sql: String,
    val args: Array<Any?>
)

abstract class BaseStatement : Statement {
    override fun renderSql(): String = render().sql
    override fun renderArgs(): Array<Any?> = render().args
    abstract fun render(): RenderResult
}

@JsExport
class CreateTableStatement(private val table: Table) : BaseStatement() {
    override fun render(): RenderResult {
        val definitions = table.columns.joinToString(", ") { col ->
            val sb = StringBuilder("${col.name} ${col.type.sqlString}")
            if (col.definition.isPrimaryKey) sb.append(" PRIMARY KEY")
            if (col.definition.isAutoIncrement) sb.append(" AUTOINCREMENT")
            if (!col.definition.isNullable) sb.append(" NOT NULL")
            col.definition.defaultValue?.let { sb.append(" DEFAULT $it") }
            sb.toString()
        }
        return RenderResult("CREATE TABLE IF NOT EXISTS ${table.tableName} ($definitions)", emptyArray())
    }
}

@JsExport
class DropTableStatement(private val table: Table) : BaseStatement() {
    override fun render(): RenderResult {
        return RenderResult("DROP TABLE IF EXISTS ${table.tableName}", emptyArray())
    }
}

@JsExport
class SelectStatement(
    private val table: Table,
    private val columns: List<Column<*>>,
    private val where: Expression,
    private val limit: Int? = null,
    private val offset: Int? = null
) : BaseStatement() {
    override fun render(): RenderResult {
        val cols = if (columns.isEmpty()) "*" else columns.joinToString(", ") { it.name }
        val args = mutableListOf<Any?>()
        val whereClause = renderExpression(where, args)
        var sql = "SELECT $cols FROM ${table.tableName}" +
                if (whereClause.isNotEmpty()) " WHERE $whereClause" else ""

        if (limit != null) sql += " LIMIT $limit"
        if (offset != null) sql += " OFFSET $offset"

        return RenderResult(sql, args.toTypedArray())
    }
}

@JsExport
class InsertStatement(
    private val table: Table,
    private val values: Map<Column<*>, Any?>
) : BaseStatement() {
    override fun render(): RenderResult {
        val cols = values.keys.joinToString(", ") { it.name }
        val placeholders = values.keys.joinToString(", ") { "?" }
        val args = values.values.toTypedArray()
        val sql = "INSERT INTO ${table.tableName} ($cols) VALUES ($placeholders)"
        return RenderResult(sql, args)
    }
}

@JsExport
class UpdateStatement(
    private val table: Table,
    private val values: Map<Column<*>, Any?>,
    private val where: Expression
) : BaseStatement() {
    override fun render(): RenderResult {
        val args = mutableListOf<Any?>()
        val sets = values.entries.joinToString(", ") {
            args.add(it.value)
            "${it.key.name} = ?"
        }
        val whereClause = renderExpression(where, args)
        val sql = "UPDATE ${table.tableName} SET $sets" +
                if (whereClause.isNotEmpty()) " WHERE $whereClause" else ""
        return RenderResult(sql, args.toTypedArray())
    }
}

@JsExport
class DeleteStatement(
    private val table: Table,
    private val where: Expression
) : BaseStatement() {
    override fun render(): RenderResult {
        val args = mutableListOf<Any?>()
        val whereClause = renderExpression(where, args)
        val sql = "DELETE FROM ${table.tableName}" +
                if (whereClause.isNotEmpty()) " WHERE $whereClause" else ""
        return RenderResult(sql, args.toTypedArray())
    }
}

private fun renderExpression(expr: Expression, args: MutableList<Any?>): String {
    return when (expr) {
        is Expression.Eq<*> -> { args.add(expr.value); "${expr.column.name} = ?" }
        is Expression.Neq<*> -> { args.add(expr.value); "${expr.column.name} != ?" }
        is Expression.Gt<*> -> { args.add(expr.value); "${expr.column.name} > ?" }
        is Expression.Lt<*> -> { args.add(expr.value); "${expr.column.name} < ?" }
        is Expression.Gte<*> -> { args.add(expr.value); "${expr.column.name} >= ?" }
        is Expression.Lte<*> -> { args.add(expr.value); "${expr.column.name} <= ?" }
        is Expression.Like -> { args.add(expr.value); "${expr.column.name} LIKE ?" }
        is Expression.And -> "(${renderExpression(expr.left, args)} AND ${renderExpression(expr.right, args)})"
        is Expression.Or -> "(${renderExpression(expr.left, args)} OR ${renderExpression(expr.right, args)})"
        Expression.Empty -> ""
    }
}

@JsExport
object SqlBuilder {
    fun create(table: Table) = CreateTableStatement(table)
    fun drop(table: Table) = DropTableStatement(table)
    fun select(columns: Array<Column<*>>) = SelectBuilder(columns.toList())
    fun insert(table: Table) = InsertBuilder(table)
    fun update(table: Table) = UpdateBuilder(table)
    fun delete(table: Table) = DeleteBuilder(table)
}

@JsExport
class SelectBuilder(private val columns: List<Column<*>>) {
    fun from(table: Table) = SelectFromBuilder(table, columns)
}

@JsExport
class SelectFromBuilder(private val table: Table, private val columns: List<Column<*>>) {
    fun where(expression: Expression) = SelectStatement(table, columns, expression)
    fun all() = SelectStatement(table, columns, Expression.Empty)
    fun limit(limit: Int, offset: Int = 0) = SelectStatement(table, columns, Expression.Empty, limit, offset)
}

@JsExport
class InsertBuilder(private val table: Table) {
    private val values = mutableMapOf<Column<*>, Any?>()

    @JsName("set")
    fun <T> set(column: Column<T>, value: T): InsertBuilder {
        values[column] = value
        return this
    }

    fun build() = InsertStatement(table, values)
}

@JsExport
class UpdateBuilder(private val table: Table) {
    private val values = mutableMapOf<Column<*>, Any?>()

    @JsName("set")
    fun <T> set(column: Column<T>, value: T): UpdateBuilder {
        values[column] = value
        return this
    }

    fun where(expression: Expression) = UpdateStatement(table, values, expression)
}

@JsExport
class DeleteBuilder(private val table: Table) {
    fun where(expression: Expression) = DeleteStatement(table, expression)
    fun all() = DeleteStatement(table, Expression.Empty)
}