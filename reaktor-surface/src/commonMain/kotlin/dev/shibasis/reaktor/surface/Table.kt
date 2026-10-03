package dev.shibasis.reaktor.surface

data class Sort(val column: String, val descending: Boolean)

fun Sort?.next(column: String): Sort? = when {
    this == null || this.column != column -> Sort(column, descending = false)
    !descending -> Sort(column, descending = true)
    else -> null
}

data class TableLayout(val sort: Sort? = null, val widths: Map<String, Float> = emptyMap())
