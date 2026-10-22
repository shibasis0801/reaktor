package dev.shibasis.dependeasy.model

import java.io.File

object ClickHouse {
    fun command(spec: MigrationSpec, root: File, readOnly: Boolean): ProcessExecution {
        val database = spec.database as DatabaseMigrations.ClickHouse
        return kubectl(spec, root, listOf("exec", "-i", "-n", database.namespace, database.pod, "--",
            "clickhouse-client", "--multiquery", "--max_execution_time=15",
            "--param_database=${database.databases.getValue(spec.environment)}") +
            if (readOnly) listOf("--readonly=1", "--format", "JSON") else emptyList())
    }

    fun namespace(spec: MigrationSpec, root: File): ProcessExecution = kubectl(spec, root,
        listOf("get", "namespace", (spec.database as DatabaseMigrations.ClickHouse).namespace, "-o", "jsonpath={.metadata.uid}"))

    private fun kubectl(spec: MigrationSpec, root: File, arguments: List<String>): ProcessExecution {
        val access = root.resolve(requireNotNull(spec.kubeconfig))
        require(access.isFile && access.canRead()) { "Declared Kubernetes access is unavailable" }
        return ProcessExecution(root.absolutePath, "kubectl", listOf("--kubeconfig", access.absolutePath,
            "--request-timeout=20s") + arguments,
            emptyList(), ToolRef("kubectl", "host-provided (unversioned)"), emptyList(), emptyList())
    }
}
