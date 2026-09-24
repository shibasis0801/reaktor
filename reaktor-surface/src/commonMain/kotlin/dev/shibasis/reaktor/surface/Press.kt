package dev.shibasis.reaktor.surface

data class PressProperties(val enabled: Boolean = true, val busy: Boolean = false, val holdable: Boolean = false)

data class PressState(
    val focused: Boolean = false,
    val focusVisible: Boolean = false,
    val hovered: Boolean = false,
    val contacts: Set<Long> = emptySet(),
    val lastActivation: Long = 0,
) {
    val pressed: Boolean get() = contacts.isNotEmpty()
}

sealed interface PressInput {
    data class Press(val contact: Long) : PressInput
    data class Release(val contact: Long) : PressInput
    data class Cancel(val contact: Long) : PressInput
    data class Focus(val focused: Boolean, val visible: Boolean) : PressInput
    data class Hover(val inside: Boolean) : PressInput
    data class Activate(val sequence: Long) : PressInput
    data class Hold(val sequence: Long) : PressInput
}

sealed interface Pressed

data object Activated : Pressed

data object Held : Pressed

object PressKernel : BehaviorKernel<PressProperties, PressState, PressInput, Pressed> {
    override fun initial(properties: PressProperties) = PressState()

    override fun reduce(properties: PressProperties, state: PressState, input: PressInput): Reduction<PressState, Pressed> =
        when (input) {
            is PressInput.Press -> Reduction(if (properties.enabled) state.copy(contacts = state.contacts + input.contact) else state)
            is PressInput.Release -> Reduction(state.copy(contacts = state.contacts - input.contact))
            is PressInput.Cancel -> Reduction(state.copy(contacts = state.contacts - input.contact))
            is PressInput.Focus -> Reduction(state.copy(focused = input.focused, focusVisible = input.focused && input.visible))
            is PressInput.Hover -> Reduction(state.copy(hovered = input.inside))
            is PressInput.Activate -> activate(properties, state, input.sequence)
            is PressInput.Hold -> hold(properties, state, input.sequence)
        }

    override fun reconcile(properties: PressProperties, state: PressState): Reduction<PressState, Pressed> =
        Reduction(if (properties.enabled) state else state.copy(contacts = emptySet(), hovered = false))

    private fun activate(properties: PressProperties, state: PressState, sequence: Long): Reduction<PressState, Pressed> =
        if (!properties.enabled || properties.busy || sequence <= state.lastActivation) Reduction(state)
        else Reduction(state.copy(lastActivation = sequence), listOf(Activated), listOf(FeedbackCue.Impact))

    private fun hold(properties: PressProperties, state: PressState, sequence: Long): Reduction<PressState, Pressed> =
        if (!properties.holdable || !properties.enabled || properties.busy || sequence <= state.lastActivation) Reduction(state)
        else Reduction(state.copy(lastActivation = sequence, contacts = emptySet()), listOf(Held), listOf(FeedbackCue.Threshold))
}
