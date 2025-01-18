package dev.shibasis.reaktor.db.sql

class RawExpression(private val rawSql: String) : Expression() {
    override fun toSql() = rawSql
}

infix fun <T> Column<T>.eq(value: T): Expression =
    RawExpression("${toSql()} = ${renderValue(value, type)}")

infix fun <T> Column<T>.neq(value: T): Expression =
    RawExpression("${toSql()} <> ${renderValue(value, type)}")

infix fun <T : Comparable<T>> Column<T>.gt(value: T): Expression =
    RawExpression("${toSql()} > ${renderValue(value, type)}")

infix fun <T : Comparable<T>> Column<T>.lt(value: T): Expression =
    RawExpression("${toSql()} < ${renderValue(value, type)}")

infix fun <T : Comparable<T>> Column<T>.gte(value: T): Expression =
    RawExpression("${toSql()} >= ${renderValue(value, type)}")

infix fun <T : Comparable<T>> Column<T>.lte(value: T): Expression =
    RawExpression("${toSql()} <= ${renderValue(value, type)}")

infix fun Expression.and(other: Expression): Expression =
    RawExpression("(${this.toSql()} AND ${other.toSql()})")

infix fun Expression.or(other: Expression): Expression =
    RawExpression("(${this.toSql()} OR ${other.toSql()})")

fun count(column: Column<*>): Expression =
    RawExpression("COUNT(${column.toSql()})")

fun max(column: Column<*>): Expression =
    RawExpression("MAX(${column.toSql()})")

fun min(column: Column<*>): Expression =
    RawExpression("MIN(${column.toSql()})")

fun sum(column: Column<*>): Expression =
    RawExpression("SUM(${column.toSql()})")

fun avg(column: Column<*>): Expression =
    RawExpression("AVG(${column.toSql()})")


fun <T> renderValue(value: T?, type: SqlType<T>): String = when {
    value == null -> "NULL"
    type is StringType -> "'${value.toString().replace("'", "''")}'"
    type is BoolType -> if (value == true) "TRUE" else "FALSE"
    else -> value.toString()
}


enum class SortOrder(val sql: String) {
    ASC("ASC"), DESC("DESC")
}

