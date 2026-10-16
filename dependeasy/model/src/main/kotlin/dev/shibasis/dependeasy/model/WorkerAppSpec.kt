package dev.shibasis.dependeasy.model

data class WorkerContractTests(
    val configuration: String,
    val environmentFiles: Map<Environment, String> = emptyMap(),
)

data class WorkerAppSpec(
    val worker: WorkerRef,
    val environments: List<Environment>,
    val typescript: List<String>,
    val tests: PackageScript?,
)
