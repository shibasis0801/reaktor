package dev.shibasis.reaktor.framework.db

import dev.shibasis.reaktor.framework.toKotlinInstant
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Function
import org.jetbrains.exposed.v1.core.IDateColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Custom Exposed column type for storing a kotlin.time.Instant
 * in a column that is timezone-aware (e.g., TIMESTAMP WITH TIME ZONE in PostgreSQL).
 */
class KotlinInstantWithTimeZoneColumnType: ColumnType<Instant>(), IDateColumnType {
    override val hasTimePart: Boolean = true

    // This is the key: it uses the SQL type for timezone-aware timestamps.
    override fun sqlType(): String = currentDialect.dataTypeProvider.timestampWithTimeZoneType()

    // Converts a value from the database to an Instant.
    // The JDBC driver will often return OffsetDateTime for timestamptz columns.
    override fun valueFromDB(value: Any): Instant = when (value) {
        is OffsetDateTime -> value.toInstant().toKotlinInstant()
        is java.sql.Timestamp -> value.toInstant().toKotlinInstant()
        is String -> Instant.parse(value)
        is java.sql.Date -> value.toKotlinInstant()
        else -> error("Unexpected value for Instant: $value of ${value::class.qualifiedName}")
    }

    // Converts an Instant from your application code to a database-compatible object.
    // We convert our Instant to an OffsetDateTime at UTC, as this is a standard
    // representation that JDBC drivers handle correctly for timestamptz.
    override fun notNullValueToDB(value: Instant): Any {
        return OffsetDateTime.ofInstant(value.toJavaInstant(), ZoneOffset.UTC)
    }

    // Provides a default string representation for SQL statements.
    override fun nonNullValueAsDefaultString(value: Instant): String {
        val instantAsStr = notNullValueToDB(value).toString()
        return "'$instantAsStr'::${sqlType()}"
    }
}

fun Table.timestampZ(name: String): Column<Instant> {
    return registerColumn(name, KotlinInstantWithTimeZoneColumnType())
}

object CurrentTimestamp : Function<Instant>(KotlinInstantWithTimeZoneColumnType()) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("CURRENT_TIMESTAMP")
    }
}
