package dev.shibasis.reaktor.core.utils

import kotlin.time.Clock


fun epochTime() = Clock.System.now().toEpochMilliseconds()
