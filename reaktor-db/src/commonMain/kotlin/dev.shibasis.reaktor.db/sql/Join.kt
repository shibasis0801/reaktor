package dev.shibasis.reaktor.db.sql

enum class JoinType(val keyword: String) {
    INNER("INNER"),
    LEFT("LEFT"),
    RIGHT("RIGHT"),
    FULL("FULL")
}

data class JoinClause(val table: Table, val type: JoinType, val on: Expression)
