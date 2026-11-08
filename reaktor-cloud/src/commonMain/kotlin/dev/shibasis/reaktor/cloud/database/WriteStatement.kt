package dev.shibasis.reaktor.cloud.database

interface WriteStatement {
    val statement: String
    val maxRows: Int
    val timeoutMillis: Long
}
