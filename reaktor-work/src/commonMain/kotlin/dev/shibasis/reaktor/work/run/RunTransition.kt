package dev.shibasis.reaktor.work.run

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface RunTransition {
    @Serializable
    @SerialName("state")
    data class State(val previous: RunState?, val current: RunState) : RunTransition

    @Serializable
    @SerialName("step")
    data class Step(val path: String, val previous: StepState, val current: StepState, val attempt: Int) : RunTransition
}
