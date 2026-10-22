package dev.shibasis.dependeasy.model

import java.io.File
import java.sql.Connection
import kotlinx.serialization.encodeToString

object MigrationKernel {
    fun plan(spec: MigrationSpec, root: File, gitSha: String, runner: String, connection: Connection): MigrationPlan = try {
        connection.use {
            it.isReadOnly = true
            it.autoCommit = false
            val files = files(spec, root)
            val applied = ledger(it)
            requireNoDrift(files, applied)
            it.rollback()
            MigrationPlan(spec, files, applied, files.filter { file -> applied.none { row -> row.version == file.version } }.map { file -> file.version }, gitSha, runner)
        }
    } catch (_: Exception) { error("Cannot plan database migrations; database details withheld") }

    fun apply(plan: MigrationPlan, root: File, run: String, connection: Connection, record: (DeployReceipt) -> Unit): DeployReceipt {
        val started = System.currentTimeMillis()
        var head = plan.applied.lastOrNull()?.let { "${it.version}:${it.checksum}" }
        fun receipt(status: ReceiptStatus) = DeployReceipt(plan.spec.node, plan.spec.environment, ServiceRef(plan.spec.node.name),
            run, "migrations-apply", plan.gitSha, plan.runner, BuildModelReader.digest(BuildModelReader.json.encodeToString(plan)),
            BuildModelReader.digest(BuildModelReader.json.encodeToString(plan.files)), head, null, status,
            if (status == ReceiptStatus.Succeeded) "ledger-verified" else "migrations", started, System.currentTimeMillis(),
            failure = if (status == ReceiptStatus.Failed) "Database migration failed; database details withheld" else null)
        try {
            connection.use { db ->
                db.isReadOnly = false
                db.autoCommit = false
                try {
                    require(files(plan.spec, root) == plan.files)
                    db.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(1684369509, 1835624306)") }
                    val applied = ledger(db)
                    require(applied == plan.applied)
                    requireNoDrift(plan.files, applied)
                    val pending = plan.files.filter { file -> applied.none { it.version == file.version } }
                    require(pending.map { it.version } == plan.pending)
                    if (pending.isNotEmpty()) {
                        db.createStatement().use { it.execute("""
                            CREATE TABLE IF NOT EXISTS public.schema_migrations (
                                version text PRIMARY KEY, name text NOT NULL, checksum text NOT NULL,
                                applied_at timestamptz NOT NULL DEFAULT now());
                            ALTER TABLE public.schema_migrations ENABLE ROW LEVEL SECURITY;
                            REVOKE ALL ON public.schema_migrations FROM anon, authenticated;
                        """.trimIndent()) }
                        pending.forEach { file ->
                            db.createStatement().use { it.execute(root.resolve(file.path).readText()) }
                            db.prepareStatement("INSERT INTO public.schema_migrations (version, name, checksum) VALUES (?, ?, ?)").use {
                                it.setString(1, file.version); it.setString(2, file.name); it.setString(3, file.checksum); it.executeUpdate()
                            }
                        }
                    }
                    val updated = ledger(db)
                    require(updated == (applied + pending.map { AppliedMigration(it.version, it.name, it.checksum) }).sortedBy { it.version })
                    db.commit()
                    head = updated.lastOrNull()?.let { "${it.version}:${it.checksum}" }
                } catch (failure: Exception) { db.rollback(); throw failure }
            }
        } catch (_: Exception) {
            record(receipt(ReceiptStatus.Failed))
            error("Database migration failed; database details withheld")
        }
        return receipt(ReceiptStatus.Succeeded).also(record)
    }

    fun observe(plan: MigrationPlan, run: String, record: (DeployReceipt) -> Unit) {
        val now = System.currentTimeMillis()
        val service = when (val database = plan.spec.database) {
            is DatabaseMigrations.Postgres -> ServiceRef(plan.spec.node.name)
            is DatabaseMigrations.D1 -> database.deployment.service
        }
        record(DeployReceipt(plan.spec.node, plan.spec.environment, service, run, "migrations-plan",
            plan.gitSha, plan.runner, BuildModelReader.digest(BuildModelReader.json.encodeToString(plan)),
            BuildModelReader.digest(BuildModelReader.json.encodeToString(plan.files)),
            head(plan), null, ReceiptStatus.Succeeded, "ledger-observed", now, now))
    }

    internal fun head(plan: MigrationPlan): String? = plan.applied.lastOrNull()?.let {
        if (plan.spec.database is DatabaseMigrations.D1) it.name else "${it.version}:${it.checksum}"
    }

    fun planFile(root: File, node: NodeId, environment: Environment) = root.resolve(
        "${GradleProtocol.PlanDirectory}/migrations/${BuildModelReader.digest("$node:${environment.name}")}.json")

    fun retained(root: File, node: NodeId, environment: Environment): MigrationPlan =
        BuildModelReader.json.decodeFromString(planFile(root, node, environment).readText())

    fun store(root: File, plan: MigrationPlan) {
        val file = planFile(root, plan.spec.node, plan.spec.environment)
        file.parentFile.mkdirs()
        val temporary = File.createTempFile(".migrations-plan-", ".json", file.parentFile)
        try {
            temporary.writeText(BuildModelReader.json.encodeToString(plan))
            java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    internal fun files(spec: MigrationSpec, root: File): List<MigrationFile> {
        val directory = root.resolve(spec.database.directory).canonicalFile
        require(directory.toPath().startsWith(root.canonicalFile.toPath()) && directory.isDirectory)
        return directory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9]{4}_[a-z0-9_]+\\.sql")) }.sortedBy { it.name }.map {
            require(it.canonicalFile.toPath().startsWith(directory.toPath()) && it.isFile)
            MigrationFile(it.relativeTo(root).invariantSeparatorsPath, it.name.take(4), it.name.substring(5).removeSuffix(".sql"), BuildModelReader.digest(it))
        }.also { files -> require(files.map { it.version }.distinct().size == files.size) }
    }

    private fun ledger(db: Connection): List<AppliedMigration> {
        val present = db.createStatement().use { statement -> statement.executeQuery("SELECT to_regclass('public.schema_migrations') IS NOT NULL").use { it.next(); it.getBoolean(1) } }
        if (!present) return emptyList()
        return db.createStatement().use { statement -> statement.executeQuery("SELECT version, name, checksum FROM public.schema_migrations ORDER BY version").use { rows ->
            buildList { while (rows.next()) add(AppliedMigration(rows.getString(1), rows.getString(2), rows.getString(3))) }
        } }
    }

    private fun requireNoDrift(files: List<MigrationFile>, applied: List<AppliedMigration>) {
        require(files.all { file -> applied.singleOrNull { it.version == file.version }?.let { it.checksum == file.checksum && it.name == file.name } != false })
    }
}
