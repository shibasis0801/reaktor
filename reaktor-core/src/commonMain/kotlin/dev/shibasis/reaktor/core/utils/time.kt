package dev.shibasis.reaktor.core.utils

import kotlinx.datetime.Clock


fun epochTime() = Clock.System.now().toEpochMilliseconds()