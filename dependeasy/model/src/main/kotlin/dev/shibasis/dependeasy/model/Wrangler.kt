package dev.shibasis.dependeasy.model

object Wrangler {
    val Command: String get() = java.io.File(System.getProperty("user.home"), ".reaktor/kernel/sidecar/wrangler").path

    private fun command(spec: WorkerDeploySpec, arguments: List<String>) = spec.execution.copy(
        arguments = arguments + listOf("--config", spec.configuration) + spec.wranglerEnvironment?.let { listOf("--env", it) }.orEmpty())

    fun dryRun(spec: WorkerDeploySpec, directory: String) = command(spec,
        listOf("deploy", "--dry-run", "--outdir", directory, "--minify"))
    fun secrets(spec: WorkerDeploySpec) = command(spec, listOf("secret", "list", "--format", "json"))
    fun putSecrets(spec: WorkerDeploySpec) = command(spec, listOf("secret", "bulk"))
    fun d1Query(spec: WorkerDeploySpec, binding: String, query: String) = command(spec,
        listOf("d1", "execute", binding, "--remote", "--json", "--command", query))
    fun d1Migrations(spec: WorkerDeploySpec, binding: String) = command(spec,
        listOf("d1", "migrations", "apply", binding, "--remote"))
    fun deployments(spec: WorkerDeploySpec) = command(spec, listOf("deployments", "list", "--json"))
    fun version(spec: WorkerDeploySpec, version: String) = command(spec, listOf("versions", "view", version, "--json"))
    fun apply(spec: WorkerDeploySpec, entry: String, run: String) = command(spec,
        listOf("deploy", entry, "--no-bundle", "--message", run))
    fun rollback(spec: WorkerDeploySpec, version: String, run: String) = command(spec,
        listOf("rollback", version, "--yes", "--message", run))
}
