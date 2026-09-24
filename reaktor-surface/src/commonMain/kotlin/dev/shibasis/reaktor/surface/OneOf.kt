package dev.shibasis.reaktor.surface

data class OneOfProperties(val selected: String?, val enabled: Boolean = true)

data class OneOfState(val lastChoice: Long = 0)

data class Choose(val key: String, val sequence: Long)

data class Chosen(val key: String)

object OneOfKernel : BehaviorKernel<OneOfProperties, OneOfState, Choose, Chosen> {
    override fun initial(properties: OneOfProperties) = OneOfState()

    override fun reduce(properties: OneOfProperties, state: OneOfState, input: Choose): Reduction<OneOfState, Chosen> =
        when {
            !properties.enabled || input.sequence <= state.lastChoice -> Reduction(state)
            input.key == properties.selected -> Reduction(state.copy(lastChoice = input.sequence))
            else -> Reduction(state.copy(lastChoice = input.sequence), listOf(Chosen(input.key)), listOf(FeedbackCue.Selection))
        }
}
