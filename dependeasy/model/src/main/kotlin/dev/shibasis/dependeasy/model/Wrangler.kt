package dev.shibasis.dependeasy.model

object Wrangler {
    private fun command(spec: WorkerDeploySpec, arguments: List<String>) = spec.execution.copy(
        arguments = listOf("exec", "wrangler") + arguments + listOf("--config", spec.configuration) +
            spec.wranglerEnvironment?.let { listOf("--env", it) }.orEmpty())

    fun dryRun(spec: WorkerDeploySpec, directory: String) = command(spec,
        listOf("deploy", "--dry-run", "--outdir", directory, "--minify"))
    fun secrets(spec: WorkerDeploySpec) = command(spec, listOf("secret", "list", "--format", "json"))
    fun putSecrets(spec: WorkerDeploySpec) = command(spec, listOf("secret", "bulk"))
    fun deployments(spec: WorkerDeploySpec) = command(spec, listOf("deployments", "list", "--json"))
    fun version(spec: WorkerDeploySpec, version: String) = command(spec, listOf("versions", "view", version, "--json"))
    fun apply(spec: WorkerDeploySpec, entry: String, run: String) = command(spec,
        listOf("deploy", entry, "--no-bundle", "--message", run))
    fun rollback(spec: WorkerDeploySpec, version: String, run: String) = command(spec,
        listOf("rollback", version, "--yes", "--message", run))
}
