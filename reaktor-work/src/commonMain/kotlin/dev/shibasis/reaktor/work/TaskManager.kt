package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature

var Feature.Work by CreateSlot<WorkRuntime>()
