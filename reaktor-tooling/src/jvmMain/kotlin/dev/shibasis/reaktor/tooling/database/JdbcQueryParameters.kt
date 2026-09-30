package dev.shibasis.reaktor.tooling.database

import java.sql.PreparedStatement
import java.sql.Types
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.json.Json

object JdbcQueryParameters {
    fun bind(statement: PreparedStatement, values: List<BoundQueryParameter>) {
        if (statement.parameterMetaData.parameterCount != values.size)
            throw QueryParameterException("The parameter count does not match the SQL placeholders")
        values.forEachIndexed { i, parameter ->
            val index = i + 1
            val type = when (parameter.type) {
                QueryParameterType.Text -> Types.VARCHAR
                QueryParameterType.Integer -> Types.BIGINT
                QueryParameterType.Decimal -> Types.NUMERIC
                QueryParameterType.Boolean -> Types.BOOLEAN
                QueryParameterType.Date -> Types.DATE
                QueryParameterType.Timestamp -> Types.TIMESTAMP_WITH_TIMEZONE
                QueryParameterType.Uuid, QueryParameterType.Json -> Types.OTHER
            }
            val value = parameter.value
            if (value == null) statement.setNull(index, type) else try {
                when (parameter.type) {
                    QueryParameterType.Text -> statement.setString(index, value)
                    QueryParameterType.Integer -> statement.setLong(index, value.toLong())
                    QueryParameterType.Decimal -> statement.setBigDecimal(index, value.toBigDecimal())
                    QueryParameterType.Boolean -> statement.setBoolean(index, value.toBooleanStrict())
                    QueryParameterType.Uuid -> statement.setObject(index, UUID.fromString(value))
                    QueryParameterType.Date -> statement.setObject(index, LocalDate.parse(value))
                    QueryParameterType.Timestamp -> statement.setObject(index, OffsetDateTime.parse(value))
                    QueryParameterType.Json -> { Json.parseToJsonElement(value); statement.setObject(index, value, Types.OTHER) }
                }
            } catch (failure: IllegalArgumentException) {
                throw QueryParameterException("Parameter $index is not a valid ${parameter.type}")
            } catch (failure: java.time.format.DateTimeParseException) {
                throw QueryParameterException("Parameter $index is not a valid ${parameter.type}")
            }
        }
    }
}

class QueryParameterException(message: String) : IllegalArgumentException(message)
