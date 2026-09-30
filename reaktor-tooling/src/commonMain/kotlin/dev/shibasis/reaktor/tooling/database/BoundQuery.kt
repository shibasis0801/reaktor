package dev.shibasis.reaktor.tooling.database

import kotlinx.serialization.Serializable

@Serializable
enum class QueryParameterType { Text, Integer, Decimal, Boolean, Uuid, Date, Timestamp, Json }

@Serializable
data class BoundQueryParameter(val type: QueryParameterType = QueryParameterType.Text, val value: String? = null)

/** This payload belongs only in a sealed private file, never an operational snapshot. */
@Serializable
data class BoundQuery(val statement: String, val parameters: List<BoundQueryParameter>) {
    fun validate() {
        require(parameters.size in 1..64) { "Bind 1–64 parameters in placeholder order" }
        require(parameters.sumOf { it.value?.encodeToByteArray()?.size ?: 0 } <= 131_072) { "Parameter values exceed 128 KiB" }
        require(parameters.none { it.value?.contains('\u0000') == true }) { "Parameter values cannot contain NUL" }
        require(SqlReadStatement.normalize(statement) == statement) { "Bound SQL must be one normalized read statement" }
    }
}
