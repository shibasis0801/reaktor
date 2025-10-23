package dev.shibasis.reaktor.navigation.capabilities

import dev.shibasis.reaktor.core.capabilities.Capability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update


sealed class Lifecycle {
    object Created: Lifecycle()
    object Restoring: Lifecycle()
    object Attached: Lifecycle()
    object Saving: Lifecycle()
    object Destroyed: Lifecycle()
}


interface LifecycleCapability: Capability {
    val lifecycle: MutableStateFlow<Lifecycle>
    val validTransitions: Set<Pair<Lifecycle, Lifecycle>>
        get() = setOf(
            Lifecycle.Created to Lifecycle.Restoring,
            Lifecycle.Restoring to Lifecycle.Attached,
            Lifecycle.Attached to Lifecycle.Saving,
            //
            Lifecycle.Saving to Lifecycle.Destroyed,
            Lifecycle.Created to Lifecycle.Destroyed,
            Lifecycle.Attached to Lifecycle.Destroyed,
            Lifecycle.Restoring to Lifecycle.Destroyed
        )

    fun transition(new: Lifecycle) {
        lateinit var previous: Lifecycle
        lifecycle.update { old ->
            previous = old
            if (validTransitions.contains(old to new))
                new
            else old
        }
        if (previous != new)
            onTransition(previous, new)
    }

    fun onTransition(previous: Lifecycle, current: Lifecycle) {}
    fun save() {}
    fun restore() {}
}


class LifecycleCapabilityImpl: LifecycleCapability {
    override val lifecycle: MutableStateFlow<Lifecycle> = MutableStateFlow(Lifecycle.Created)
    override fun close() {}
}
