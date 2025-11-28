package dev.shibasis.reaktor.db.core

import kotlinx.datetime.Clock

interface TimestampProvider {
    fun getTimestamp(): Long
}

class DefaultTimestampProvider : TimestampProvider {
    override fun getTimestamp(): Long = Clock.System.now().toEpochMilliseconds()
}
