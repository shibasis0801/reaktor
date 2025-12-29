package dev.shibasis.reaktor.db.core

import kotlin.time.Clock

interface TimestampProvider {
    fun getTimestamp(): Long
}

class DefaultTimestampProvider : TimestampProvider {
    override fun getTimestamp(): Long = Clock.System.now().toEpochMilliseconds()
}
