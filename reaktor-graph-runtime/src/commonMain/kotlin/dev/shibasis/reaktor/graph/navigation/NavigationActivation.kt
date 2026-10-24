package dev.shibasis.reaktor.graph.navigation

import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

class NavigationActivation(
    val entryId: Uuid?,
    val context: CoroutineContext,
)
