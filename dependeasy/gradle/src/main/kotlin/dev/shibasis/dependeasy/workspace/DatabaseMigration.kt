package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.versions.DataLibraries
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Database migration plans observe the remote ledger")
abstract class DatabaseMigration : DefaultTask(), KernelSpecOwner {
    @get:Input abstract val declaration: Property<String>
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val migrations: ConfigurableFileCollection
    @get:Input abstract val action: Property<String>
    @get:Input @get:Optional abstract val protocolAction: Property<String>
    @get:Input @get:Optional abstract val protocolRequest: Property<String>
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal val spec: MigrationSpec get() = BuildModelReader.json.decodeFromString(declaration.get())
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.DatabaseMigration(spec,
        ToolRef("postgresql", DataLibraries.Postgres.substringAfterLast(':')))
    @get:Internal override val kernelEffect: Effect get() = Effect.Deploy
    @get:Internal override val kernelHost: HostRequirement get() = HostRequirement(HostPlatform.Portable, ExecutionHost.Local)

    @Option(option = "deploy-action", description = "Plan or apply the declared database migrations")
    fun deployAction(value: String) { require(value in listOf("plan", "apply")); action.set(value) }

    init {
        action.convention("apply")
        protocolAction.convention(project.providers.gradleProperty(GradleProtocol.DeployActionOption))
        protocolRequest.convention(project.providers.gradleProperty(GradleProtocol.DeployRequestOption))
        rootDirectory.convention(project.rootProject.layout.projectDirectory)
        outputs.upToDateWhen { false }
    }

    @TaskAction fun migrate() {
        val root = rootDirectory.get().asFile
        val spec = spec
        val run = UUID.randomUUID().toString()
        val record: (DeployReceipt) -> Unit = { WorkerDeployKernel.record(root, it) }
        val database = spec.database as DatabaseMigrations.Postgres
        when (protocolAction.orNull ?: action.get()) {
            "plan" -> {
                val revision = ByteArrayOutputStream()
                execOperations.exec {
                    workingDir = root; executable = "git"; args("rev-parse", "HEAD")
                    standardOutput = revision; errorOutput = OutputStream.nullOutputStream()
                }.assertNormalExitValue()
                val plan = MigrationKernel.plan(spec, root, revision.toString().trim(),
                    java.net.InetAddress.getLocalHost().hostName, Postgres.connect(root, database, spec.environment))
                MigrationKernel.store(root, plan)
                MigrationKernel.observe(plan, run, record)
                logger.lifecycle("Migrations {}: pending {}", spec.node, plan.pending)
            }
            "apply" -> {
                val request = requireNotNull(protocolRequest.orNull) { "Database migrations require a reviewed build plan" }
                val plan = BuildModelReader.readRequest(java.io.File(request)).migrations.single { it.spec.node == spec.node }
                require(plan.spec == spec) { "Database migration declaration changed after planning" }
                MigrationKernel.apply(plan, root, run, Postgres.connect(root, database, spec.environment), record)
            }
            else -> error("Unsupported database migration action")
        }
    }
}

internal fun Project.databaseMigration(name: String, database: DatabaseMigrations): NodeHandle {
    val selected = option(BuildOption.string(GradleProtocol.EnvironmentOption, "dev"))
    val site = declarationSite()
    val id = NodeId(rootProject.name, path, name)
    val migration = tasks.register(name, DatabaseMigration::class.java) {
        group = "delivery"
        declaration.set(selected.map { environment -> BuildModelReader.json.encodeToString(
            MigrationSpec(id, Environment(environment), database)) })
        when (database) {
            is DatabaseMigrations.Postgres -> migrations.from(fileTree(database.directory) { include("*.sql") })
            is DatabaseMigrations.D1 -> error("Declare D1 migrations inside a worker fleet")
        }
        site?.let { extensions.add(SourceSite::class.java, "declarationSite", it) }
    }
    return node(migration).also { BuildWorkspace.get(this).alias(rootDir, name, it) }
}
