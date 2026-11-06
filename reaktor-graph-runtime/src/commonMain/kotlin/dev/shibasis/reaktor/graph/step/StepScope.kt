package dev.shibasis.reaktor.graph.step

class StepScope(
    val effectKey: String,
    val attempt: Int,
    val checkpoint: String?,
    private val save: suspend (String) -> Unit,
) {
    suspend fun checkpoint(value: String) = save(value)

    fun fail(reason: String): Nothing = throw StepFailed(reason)
}

class StepFailed(val reason: String) : Exception(reason)
