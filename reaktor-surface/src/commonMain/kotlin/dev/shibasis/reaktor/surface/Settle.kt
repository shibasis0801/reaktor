package dev.shibasis.reaktor.surface

data class SettleProperties(val threshold: Float = 0.35f, val flingVelocity: Float = 1200f)

data class Release(val offset: Float, val extent: Float, val velocity: Float)

sealed interface SettleEvent {
    data object Dismiss : SettleEvent
    data object Restore : SettleEvent
}

object SettleKernel : BehaviorKernel<SettleProperties, Unit, Release, SettleEvent> {
    override fun initial(properties: SettleProperties) = Unit

    override fun reduce(properties: SettleProperties, state: Unit, input: Release): Reduction<Unit, SettleEvent> =
        if (input.velocity > properties.flingVelocity || input.offset > input.extent * properties.threshold) Reduction(Unit, listOf(SettleEvent.Dismiss))
        else Reduction(Unit, listOf(SettleEvent.Restore))
}
