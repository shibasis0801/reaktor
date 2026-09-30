package dev.shibasis.reaktor.tooling.database

import java.math.BigDecimal
import java.math.MathContext
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneOffset

enum class QueryPlotAxis { Row, Number, Time }
data class QueryPlotPoint(val row: Int, val x: BigDecimal, val y: BigDecimal?)
data class QueryPlotSeries(val column: Int, val points: List<QueryPlotPoint>, val missing: Int)

object QueryPlot {
    fun series(rows: List<List<String?>>, xColumn: Int, yColumns: List<Int>, axis: QueryPlotAxis): List<QueryPlotSeries> {
        require(rows.size <= 500 && yColumns.size in 1..4 && yColumns.distinct().size == yColumns.size)
        return yColumns.map { column ->
            require(column >= 0)
            val points = rows.mapIndexedNotNull { index, row ->
                val x = when (axis) {
                    QueryPlotAxis.Row -> (index + 1).toBigDecimal()
                    QueryPlotAxis.Number -> row.getOrNull(xColumn)?.let(::number)
                    QueryPlotAxis.Time -> row.getOrNull(xColumn)?.let(::time)
                } ?: return@mapIndexedNotNull null
                QueryPlotPoint(index, x, row.getOrNull(column)?.let(::number))
            }.sortedBy { it.x }
            QueryPlotSeries(column, points, rows.size - points.count { it.y != null })
        }
    }

    fun fraction(value: BigDecimal, minimum: BigDecimal, maximum: BigDecimal): Double =
        if (minimum.compareTo(maximum) == 0) .5 else value.subtract(minimum).divide(maximum.subtract(minimum), MathContext.DECIMAL64).toDouble()

    fun number(value: String): BigDecimal? = value.takeIf { it.length <= 4096 }?.toBigDecimalOrNull()
        ?.takeIf { it.precision() <= 1024 && it.scale() in -1024..1024 }

    private fun time(value: String): BigDecimal? = runCatching {
        val instant = runCatching { OffsetDateTime.parse(value.replace(' ', 'T')).toInstant() }.getOrElse {
            LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
        }
        instant.epochSecond.toBigDecimal() + instant.nano.toBigDecimal().movePointLeft(9)
    }.getOrNull()
}
