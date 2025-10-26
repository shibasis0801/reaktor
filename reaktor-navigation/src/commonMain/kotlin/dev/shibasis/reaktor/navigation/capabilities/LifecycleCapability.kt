package dev.shibasis.reaktor.navigation.capabilities

import dev.shibasis.reaktor.core.capabilities.Capability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update


sealed class Lifecycle {
    object Created: Lifecycle()
    object Restoring: Lifecycle()
    object Attaching: Lifecycle()
    object Saving: Lifecycle()
    object Destroying: Lifecycle()
}


interface LifecycleCapability: Capability {
    val lifecycle: MutableStateFlow<Lifecycle>
    val validTransitions: Set<Pair<Lifecycle, Lifecycle>>
        get() = setOf(
            Lifecycle.Created to Lifecycle.Restoring,
            Lifecycle.Restoring to Lifecycle.Attaching,
            Lifecycle.Attaching to Lifecycle.Saving,
            //
            Lifecycle.Saving to Lifecycle.Destroying,
            Lifecycle.Created to Lifecycle.Destroying,
            Lifecycle.Attaching to Lifecycle.Destroying,
            Lifecycle.Restoring to Lifecycle.Destroying
        )

    fun transition(next: Lifecycle) {
        lateinit var previous: Lifecycle
        lifecycle.update { old ->
            previous = old
            if (validTransitions.contains(old to next))
                next
            else old
        }
        if (previous != next)
            onTransition(previous, next)
    }

    fun onTransition(previous: Lifecycle, next: Lifecycle) {}
    fun save() {}
    fun restore() {}
}


class LifecycleCapabilityImpl: LifecycleCapability {
    override val lifecycle: MutableStateFlow<Lifecycle> = MutableStateFlow(Lifecycle.Created)
    override fun close() {}
}
