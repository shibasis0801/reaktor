package dev.shibasis.reaktor.surface

data class ManyOfProperties(val selected: Set<String>, val capacity: Int = Int.MAX_VALUE, val enabled: Boolean = true)

data class ManyOfState(val lastChoice: Long = 0)

sealed interface ManyOfEvent {
    data class Change(val selected: Set<String>) : ManyOfEvent
    data class Refused(val key: String) : ManyOfEvent
}

object ManyOfKernel : BehaviorKernel<ManyOfProperties, ManyOfState, Choose, ManyOfEvent> {
    override fun initial(properties: ManyOfProperties) = ManyOfState()

    override fun reduce(properties: ManyOfProperties, state: ManyOfState, input: Choose): Reduction<ManyOfState, ManyOfEvent> {
        if (!properties.enabled || input.sequence <= state.lastChoice) return Reduction(state)
        val next = state.copy(lastChoice = input.sequence)
        return when {
            input.key in properties.selected ->
                Reduction(next, listOf(ManyOfEvent.Change(properties.selected - input.key)), listOf(FeedbackCue.Selection))
            properties.selected.size >= properties.capacity ->
                Reduction(next, listOf(ManyOfEvent.Refused(input.key)), listOf(FeedbackCue.Threshold))
            else ->
                Reduction(next, listOf(ManyOfEvent.Change(properties.selected + input.key)), listOf(FeedbackCue.Selection))
        }
    }
}
