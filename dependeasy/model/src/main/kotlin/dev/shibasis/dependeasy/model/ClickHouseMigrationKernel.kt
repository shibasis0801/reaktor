package dev.shibasis.dependeasy.model

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object ClickHouseMigrationKernel {
    fun plan(spec: MigrationSpec, root: File, gitSha: String, runner: String,
        execute: (ProcessExecution, String) -> String): MigrationPlan = try {
        val files = MigrationKernel.files(spec, root)
        MigrationPlan(spec, files, emptyList(), files.map { it.version }, gitSha, runner,
            observed(spec, root, execute))
    } catch (_: Exception) { error("Cannot plan ClickHouse SQL; database details withheld") }

    fun apply(plan: MigrationPlan, root: File, run: String, execute: (ProcessExecution, String) -> String,
        record: (DeployReceipt) -> Unit): DeployReceipt {
        val started = System.currentTimeMillis()
        val database = plan.spec.database as DatabaseMigrations.ClickHouse
        var head = plan.configurationDigest
        fun receipt(status: ReceiptStatus) = DeployReceipt(plan.spec.node, plan.spec.environment, database.service,
            run, "migrations-apply", plan.gitSha, plan.runner, BuildModelReader.digest(BuildModelReader.json.encodeToString(plan)),
            BuildModelReader.digest(BuildModelReader.json.encodeToString(plan.files)), head, null, status,
            if (status == ReceiptStatus.Succeeded) "sql-applied" else "sql", started, System.currentTimeMillis(),
            failure = if (status == ReceiptStatus.Failed) "ClickHouse SQL failed; database details withheld" else null)
        try {
            require(ClickHouseMigrationKernel.plan(plan.spec, root, plan.gitSha, plan.runner, execute) == plan) { "ClickHouse SQL or schema changed after planning" }
            val command = ClickHouse.command(plan.spec, root, false)
            plan.files.forEach { execute(command, root.resolve(it.path).readText()
                .replace("{database:Identifier}", database.databases.getValue(plan.spec.environment))) }
            head = observed(plan.spec, root, execute)
        } catch (_: Exception) {
            runCatching { observed(plan.spec, root, execute) }.getOrNull()?.let { head = it }
            record(receipt(ReceiptStatus.Failed))
            error("ClickHouse SQL failed; database details withheld")
        }
        return receipt(ReceiptStatus.Succeeded).also(record)
    }

    private fun observed(spec: MigrationSpec, root: File, execute: (ProcessExecution, String) -> String): String {
        val command = ClickHouse.command(spec, root, true)
        fun query(sql: String): JsonArray {
            val result = BuildModelReader.json.parseToJsonElement(execute(command, sql)).jsonObject
            return result.getValue("data").jsonArray
        }
        val facts = buildJsonObject {
            val namespace = execute(ClickHouse.namespace(spec, root), "").trim()
            require(namespace.isNotEmpty()) { "Kubernetes namespace identity is unavailable" }
            put("namespace", namespace)
            put("database", query("SELECT name FROM system.databases WHERE name = {database:String} ORDER BY name"))
            put("tables", query("SELECT name, engine, metadata_modification_time FROM system.tables WHERE database = {database:String} ORDER BY name"))
            put("columns", query("SELECT table, name, type, position FROM system.columns WHERE database = {database:String} ORDER BY table, position"))
        }
        return BuildModelReader.digest(facts.toString())
    }
}
