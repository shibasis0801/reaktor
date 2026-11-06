package dev.shibasis.reaktor.graph.step

import kotlinx.serialization.Serializable

@Serializable
enum class StepState {
    Pending, Running, Waiting, Succeeded, Failed, Skipped, TimedOut, Unknown, Cancelled, Undoing, Undone;

    val settled: Boolean get() = this != Pending && this != Running && this != Waiting && this != Unknown
}
