package dev.shibasis.reaktor.cloud.database

interface QueryOptions {
    val maxRows: Int
    val explain: Boolean
    val analyze: Boolean
}
