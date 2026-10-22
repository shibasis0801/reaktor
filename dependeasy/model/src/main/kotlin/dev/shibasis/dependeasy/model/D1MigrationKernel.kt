package dev.shibasis.dependeasy.model

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object D1MigrationKernel {
    fun plan(spec: MigrationSpec, root: File, gitSha: String, runner: String,
        execute: (ProcessExecution) -> String): MigrationPlan = try {
        val database = spec.database as DatabaseMigrations.D1
        val configuration = configuration(database, root)
        val files = MigrationKernel.files(spec, root).map { it.copy(name = File(it.path).name) }
        require(root.resolve(database.directory).listFiles().orEmpty().filter { it.extension == "sql" }.size == files.size)
        val applied = ledger(database, configuration, execute)
        require(applied.all { row -> files.any { it.name == row.name } })
        MigrationPlan(spec, files, applied, files.filter { file -> applied.none { it.name == file.name } }.map { it.version },
            gitSha, runner, BuildModelReader.digest(configurationFile(database, root)))
    } catch (_: Exception) { error("Cannot plan D1 migrations; database details withheld") }

    fun apply(plan: MigrationPlan, root: File, run: String, execute: (ProcessExecution) -> String,
        record: (DeployReceipt) -> Unit): DeployReceipt {
        val started = System.currentTimeMillis()
        val database = plan.spec.database as DatabaseMigrations.D1
        var head = MigrationKernel.head(plan)
        fun receipt(status: ReceiptStatus) = DeployReceipt(plan.spec.node, plan.spec.environment, database.deployment.service,
            run, "migrations-apply", plan.gitSha, plan.runner, BuildModelReader.digest(BuildModelReader.json.encodeToString(plan)),
            BuildModelReader.digest(BuildModelReader.json.encodeToString(plan.files)), head, null, status,
            if (status == ReceiptStatus.Succeeded) "ledger-verified" else "migrations", started, System.currentTimeMillis(),
            failure = if (status == ReceiptStatus.Failed) "D1 migration failed; database details withheld" else null)
        try {
            require(D1MigrationKernel.plan(plan.spec, root, plan.gitSha, plan.runner, execute) == plan) { "D1 migration state changed after planning" }
            if (plan.pending.isNotEmpty()) execute(Wrangler.d1Migrations(database.deployment, database.binding))
            val updated = ledger(database, configuration(database, root), execute)
            head = updated.lastOrNull()?.name
            require(updated.map { it.name }.toSet() == plan.files.map { it.name }.toSet())
        } catch (_: Exception) {
            // Wrangler commits each file separately; a failed apply can have advanced the ledger.
            runCatching { ledger(database, configuration(database, root), execute) }.getOrNull()?.let { head = it.lastOrNull()?.name }
            record(receipt(ReceiptStatus.Failed))
            error("D1 migration failed; database details withheld")
        }
        return receipt(ReceiptStatus.Succeeded).also(record)
    }

    private fun configurationFile(database: DatabaseMigrations.D1, root: File) =
        root.resolve(database.deployment.execution.directory).resolve(database.deployment.configuration).canonicalFile

    private fun configuration(database: DatabaseMigrations.D1, root: File): JsonObject {
        val deployment = database.deployment
        require(deployment.wranglerEnvironment == null) { "Use a generated final D1 configuration" }
        val file = configurationFile(database, root)
        require(file.toPath().startsWith(root.canonicalFile.toPath()))
        val config = BuildModelReader.json.parseToJsonElement(file.readText()).jsonObject
        val binding = config["d1_databases"]!!.jsonArray.single { it.jsonObject["binding"]?.jsonPrimitive?.content == database.binding }.jsonObject
        require(binding["database_id"]?.jsonPrimitive?.content?.isNotBlank() == true)
        require(binding["migrations_pattern"] == null) { "Only top-level D1 migrations are declared" }
        val directory = binding["migrations_dir"]?.jsonPrimitive?.content ?: "migrations"
        require(file.parentFile.resolve(directory).canonicalFile == root.resolve(database.directory).canonicalFile)
        require((binding["migrations_table"]?.jsonPrimitive?.content ?: "d1_migrations").matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        return binding
    }

    private fun ledger(database: DatabaseMigrations.D1, configuration: JsonObject,
        execute: (ProcessExecution) -> String): List<AppliedMigration> {
        val table = configuration["migrations_table"]?.jsonPrimitive?.content ?: "d1_migrations"
        fun query(sql: String): List<JsonObject> {
            val results = BuildModelReader.json.parseToJsonElement(execute(Wrangler.d1Query(database.deployment, database.binding, sql))).jsonArray
            require(results.size == 1 && results.single().jsonObject["success"]?.jsonPrimitive?.boolean == true)
            return results.single().jsonObject["results"]!!.jsonArray.map { it.jsonObject }
        }
        if (query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'").isEmpty()) return emptyList()
        return query("SELECT name FROM \"$table\" ORDER BY id").map {
            val name = it.getValue("name").jsonPrimitive.content
            require(name.matches(Regex("[0-9]{4}_[a-z0-9_]+\\.sql")))
            AppliedMigration(name.take(4), name, "")
        }.also { require(it.map { row -> row.name }.distinct().size == it.size) }
    }
}
