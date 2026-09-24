package dev.shibasis.reaktor.surface

data class ToggleProperties(val checked: Boolean, val enabled: Boolean = true)

data class CheckedChange(val checked: Boolean)

object ToggleKernel : BehaviorKernel<ToggleProperties, PressState, PressInput, CheckedChange> {
    override fun initial(properties: ToggleProperties) = PressState()

    override fun reduce(properties: ToggleProperties, state: PressState, input: PressInput): Reduction<PressState, CheckedChange> {
        val press = PressKernel.reduce(PressProperties(properties.enabled), state, input)
        return Reduction(
            state = press.state,
            events = press.events.map { CheckedChange(!properties.checked) },
            cues = press.cues.map { FeedbackCue.Selection },
            commands = press.commands,
        )
    }

    override fun reconcile(properties: ToggleProperties, state: PressState): Reduction<PressState, CheckedChange> =
        Reduction(PressKernel.reconcile(PressProperties(properties.enabled), state).state)
}
