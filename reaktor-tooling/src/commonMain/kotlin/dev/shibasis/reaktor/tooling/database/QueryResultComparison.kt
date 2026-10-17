package dev.shibasis.reaktor.tooling.database

/** Duplicate labels retain their occurrence identity instead of collapsing into a map. */
data class ResultColumnId(val name: String, val occurrence: Int)
data class ComparisonColumn(val id: ResultColumnId, val before: QueryColumn?, val after: QueryColumn?)
enum class RowDifference { Added, Removed, Changed, Unchanged }
data class ComparedRow(val beforeIndex: Int?, val afterIndex: Int?, val difference: RowDifference, val changedColumns: List<Int>)
data class QueryResultComparison(
    val columns: List<ComparisonColumn>,
    val rows: List<ComparedRow>,
    val keyColumns: List<ResultColumnId>,
    val partial: Boolean,
) {
    val schemaChanged: Boolean get() = columns.any { it.before != it.after }
}

fun List<QueryColumn>.comparisonIds(): List<ResultColumnId> {
    val occurrences = mutableMapOf<String, Int>()
    return map { column -> ResultColumnId(column.name, occurrences.getOrElse(column.name) { 0 }).also {
        occurrences[column.name] = it.occurrence + 1
    } }
}

/** Equality is supplied by T; SQL NULL, a string "null", and a missing column remain distinct. */
fun <T> compareQueryResults(
    beforeColumns: List<QueryColumn>, beforeRows: List<List<T>>,
    afterColumns: List<QueryColumn>, afterRows: List<List<T>>,
    keyColumns: List<ResultColumnId> = emptyList(), partial: Boolean = false,
): QueryResultComparison {
    require(beforeRows.size <= 500 && afterRows.size <= 500 && beforeColumns.size <= 512 && afterColumns.size <= 512) {
        "Compare bounded result pages; export larger comparisons separately"
    }
    require(beforeRows.all { it.size == beforeColumns.size } && afterRows.all { it.size == afterColumns.size }) {
        "Result rows do not match their columns"
    }
    require(keyColumns.distinct().size == keyColumns.size) { "Comparison key columns must be distinct" }
    val beforeIds = beforeColumns.comparisonIds()
    val afterIds = afterColumns.comparisonIds()
    val left = beforeIds.withIndex().associate { it.value to it.index }
    val right = afterIds.withIndex().associate { it.value to it.index }
    val ids = (beforeIds + afterIds).distinct()
    val columns = ids.map { ComparisonColumn(it, left[it]?.let(beforeColumns::get), right[it]?.let(afterColumns::get)) }
    require(keyColumns.all { it in left && it in right }) { "Every comparison key must exist in both results" }
    require(keyColumns.all { beforeColumns[left.getValue(it)].type == afterColumns[right.getValue(it)].type }) {
        "Comparison key types differ between results"
    }
    fun keyed(rows: List<List<T>>, indexes: Map<ResultColumnId, Int>): Map<List<T>, Int> {
        val result = linkedMapOf<List<T>, Int>()
        rows.forEachIndexed { index, row ->
            val key = keyColumns.map { row[indexes.getValue(it)] }
            require(result.put(key, index) == null) { "Comparison keys are not unique; select additional columns" }
        }
        return result
    }
    val pairs = if (keyColumns.isEmpty()) (0 until maxOf(beforeRows.size, afterRows.size)).map {
        it.takeIf { it < beforeRows.size } to it.takeIf { it < afterRows.size }
    } else {
        val before = keyed(beforeRows, left)
        val after = keyed(afterRows, right)
        (before.keys + after.keys).distinct().map { before[it] to after[it] }
    }
    val rows = pairs.map { (before, after) ->
        val changed = ids.indices.filter { column ->
            val a = left[ids[column]]; val b = right[ids[column]]
            before == null || after == null || a == null || b == null ||
                beforeRows[before][a] != afterRows[after][b] || columns[column].before != columns[column].after
        }
        ComparedRow(before, after, when {
            before == null -> RowDifference.Added
            after == null -> RowDifference.Removed
            changed.isNotEmpty() -> RowDifference.Changed
            else -> RowDifference.Unchanged
        }, changed)
    }
    return QueryResultComparison(columns, rows, keyColumns, partial)
}
