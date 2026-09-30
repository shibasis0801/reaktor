package dev.shibasis.reaktor.tooling.database

import java.math.BigDecimal
import java.util.Locale

/** Local result sorting retains decimal precision and places SQL NULL last in either direction. */
object QueryValueOrder {
    fun comparator(type: String?, descending: Boolean = false): Comparator<String?> {
        val normalized = type.orEmpty().lowercase(Locale.ROOT).replace("nullable(", "").replace("lowcardinality(", "")
        val numeric = Regex("^(u?int[0-9]*|tinyint|smallint|bigint|integer|decimal|numeric|number|float[0-9]*|double|real|serial|bigserial)")
            .containsMatchIn(normalized)
        val boolean = normalized in setOf("bool", "boolean")
        fun rank(value: String): BigDecimal? = when {
            numeric -> value.toBigDecimalOrNull()
            boolean -> when (value.lowercase(Locale.ROOT)) { "true", "t", "1" -> BigDecimal.ONE; "false", "f", "0" -> BigDecimal.ZERO; else -> null }
            else -> null
        }
        return Comparator { left, right ->
            when {
                left == null && right == null -> 0
                left == null -> 1
                right == null -> -1
                else -> {
                    val a = rank(left); val b = rank(right)
                    val comparison = when {
                        a != null && b != null -> a.compareTo(b)
                        a != null -> -1
                        b != null -> 1
                        else -> left.compareTo(right)
                    }
                    if (descending) -comparison.sign() else comparison.sign()
                }
            }
        }
    }
    private fun Int.sign() = compareTo(0)
}
