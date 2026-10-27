package dev.shibasis.dependeasy.model

data class WorkerContractTests(
    val configuration: String,
    val environmentFiles: Map<Environment, String> = emptyMap(),
    val environment: Map<Environment, Map<String, String>> = emptyMap(),
    val optionalEnvironmentFiles: List<String> = emptyList(),
)

enum class WorkerContract { Auth, Smoke, Behaviour, Public }
