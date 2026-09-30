package dev.shibasis.reaktor.surface

data class DisclosureProperties(val expanded: Boolean, val enabled: Boolean = true)

data class DisclosureState(val mounted: Boolean = false, val lastSequence: Long = 0)

sealed interface DisclosureInput {
    data class Toggle(val sequence: Long) : DisclosureInput
    data class Choose(val key: String, val sequence: Long) : DisclosureInput
    data class Mounted(val first: PartKey?) : DisclosureInput
    data object Unmounted : DisclosureInput
    data object Dismiss : DisclosureInput
}

sealed interface DisclosureEvent {
    data class ExpandedChange(val expanded: Boolean) : DisclosureEvent
    data class Chosen(val key: String) : DisclosureEvent
}

object DisclosureKernel : BehaviorKernel<DisclosureProperties, DisclosureState, DisclosureInput, DisclosureEvent> {
    val Trigger = PartKey("trigger")

    override fun initial(properties: DisclosureProperties) = DisclosureState()

    override fun reduce(
        properties: DisclosureProperties,
        state: DisclosureState,
        input: DisclosureInput,
    ): Reduction<DisclosureState, DisclosureEvent> = when (input) {
        is DisclosureInput.Toggle ->
            if (!properties.enabled || input.sequence <= state.lastSequence) Reduction(state)
            else Reduction(state.copy(lastSequence = input.sequence), listOf(DisclosureEvent.ExpandedChange(!properties.expanded)))
        is DisclosureInput.Choose ->
            if (!properties.expanded || input.sequence <= state.lastSequence) Reduction(state)
            else Reduction(
                state.copy(lastSequence = input.sequence),
                listOf(DisclosureEvent.Chosen(input.key), DisclosureEvent.ExpandedChange(false)),
                listOf(FeedbackCue.Impact),
            )
        is DisclosureInput.Mounted ->
            Reduction(state.copy(mounted = true), commands = listOfNotNull(input.first?.let { LocalCommand.Focus(it) }))
        DisclosureInput.Unmounted ->
            Reduction(state.copy(mounted = false), commands = listOf(LocalCommand.Focus(Trigger)))
        DisclosureInput.Dismiss ->
            if (properties.expanded) Reduction(state, listOf(DisclosureEvent.ExpandedChange(false))) else Reduction(state)
    }
}
