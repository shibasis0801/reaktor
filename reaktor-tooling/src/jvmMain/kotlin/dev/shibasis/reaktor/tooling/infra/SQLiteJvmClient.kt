package dev.shibasis.reaktor.tooling.infra

import dev.shibasis.reaktor.tooling.DevelopmentDevice
import dev.shibasis.reaktor.tooling.DeviceTransport
import dev.shibasis.reaktor.tooling.database.*
import dev.shibasis.reaktor.tooling.device.AdbDeviceBackend
import dev.shibasis.reaktor.tooling.device.AdbDeviceSession
import dev.shibasis.reaktor.tooling.device.SimctlDeviceBackend
import dev.shibasis.reaktor.tooling.device.SimctlDeviceSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.sqlite.SQLiteConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Base64

internal class SQLiteJvmClient(private val session: InfrastructureSession) {
    fun read(op: InfrastructureOperation.DatabaseRead, query: String?, timeoutMillis: Long): QueryReceipt {
        require(!op.analyze) { "SQLite has no EXPLAIN ANALYZE; use the query plan" }
        return when (val connection = op.connection) {
            is DatabaseConnection.SQLiteFile -> open(File(connection.path).also { if (connection.ownerOnly) requireOwnerOnly(it) }, op, query, timeoutMillis)
            is DatabaseConnection.SQLiteDirectory -> {
                require(query == null) { "Select one SQLite database before querying" }
                directory(File(connection.path), op.maxRows)
            }
            is DatabaseConnection.AndroidAppDatabase -> snapshot { directory ->
                android(connection, directory)
                open(directory.resolve(File(connection.path).name).toFile(), op, query, timeoutMillis)
            }
            is DatabaseConnection.SimulatorAppDatabase -> {
                require(connection.udid.matches(Regex("[A-Fa-f0-9-]{36}"))) { "Invalid simulator UDID" }
                require(connection.application.matches(Application) && connection.path.matches(RelativePath) && ".." !in connection.path)
                val simulator = runBlocking {
                    SimctlDeviceBackend().session(DevelopmentDevice(connection.udid, connection.udid, DeviceTransport.Simctl, "Booted"))
                } as SimctlDeviceSession
                val container = runBlocking { simulator.containerPath(connection.application) }
                require(container.isNotBlank() && File(container).isDirectory) { "The app data container is unavailable on the selected simulator" }
                open(File(container, connection.path), op, query, timeoutMillis)
            }
            else -> error("Expected a SQLite connection")
        }
    }

    private fun open(file: File, op: InfrastructureOperation.DatabaseRead, query: String?, timeoutMillis: Long): QueryReceipt {
        require(file.isAbsolute && file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath())) { "SQLite database is unavailable, unreadable, or symbolic" }
        val clock = QueryClock()
        val config = SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(5_000)
        }
        val connection = session.own(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", config.toProperties()))
        connection.createStatement().use { it.execute("PRAGMA query_only=ON") }
        clock.connected()
        val sql = query?.let { (if (op.explain) "EXPLAIN QUERY PLAN " else "") + "SELECT * FROM (\n$it\n) AS reaktor_query LIMIT ${op.maxRows + 1}" }
            ?: "SELECT name AS \"table\" FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name LIMIT ${op.maxRows + 1}"
        return connection.createStatement().use { statement ->
            session.own(statement)
            statement.queryTimeout = (timeoutMillis / 1000).toInt().coerceAtLeast(1)
            statement.executeQuery(sql).use { rows ->
                clock.executed()
                val (columns, values, bytes) = collect(rows)
                QueryReceipt(provider = DatabaseProvider.SQLite.name, columns = columns, rows = values.take(op.maxRows),
                    truncated = values.size > op.maxRows,
                    metrics = clock.metrics() + QueryMetric("Cell payload", bytes.toString(), "bytes", QueryMetricSource.Client),
                    plan = if (op.explain) QueryPlan("sqlite-query-plan", buildJsonObject {
                        put("columns", JsonArray(columns.map { JsonPrimitive(it.name) }))
                        put("rows", JsonArray(values.map(::JsonArray)))
                    }) else null)
            }
        }
    }

    private fun directory(root: File, maxRows: Int): QueryReceipt {
        require(root.isAbsolute && root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "SQLite directory is unavailable" }
        val clock = QueryClock()
        val databases = root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.extension in setOf("db", "sqlite", "sqlite3") }.sortedBy { it.path }.toList()
        require(databases.isNotEmpty()) { "No SQLite databases are present below ${root.name}" }
        val rows = databases.flatMap { database ->
            val tables = DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}", SQLiteConfig().apply { setReadOnly(true) }.toProperties()).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { result ->
                        buildList { while (result.next()) add(result.getString(1)) }
                    }
                }
            }
            val relative = database.relativeTo(root).path
            tables.ifEmpty { listOf(null) }.map { table -> listOf(JsonPrimitive(relative), table?.let(::JsonPrimitive) ?: JsonNull) }
        }
        clock.executed()
        return QueryReceipt(provider = DatabaseProvider.SQLite.name, columns = listOf(QueryColumn("database", "TEXT"), QueryColumn("table", "TEXT")),
            rows = rows.take(maxRows), truncated = rows.size > maxRows, metrics = clock.metrics())
    }

    private fun collect(rows: ResultSet): Triple<List<QueryColumn>, List<List<JsonElement>>, Int> {
        val meta = rows.metaData
        require(meta.columnCount <= 512) { "Database result exceeds 512 columns" }
        val columns = (1..meta.columnCount).map { QueryColumn(meta.getColumnLabel(it), meta.getColumnTypeName(it).orEmpty().ifBlank { "unknown" }) }
        val values = mutableListOf<List<JsonElement>>()
        var bytes = 0
        while (rows.next()) {
            val row = columns.indices.map { index ->
                when (val value = rows.getObject(index + 1)) {
                    null -> JsonNull
                    is Number -> JsonPrimitive(value)
                    is ByteArray -> JsonPrimitive("base64:" + Base64.getEncoder().encodeToString(value))
                    else -> JsonPrimitive(value.toString())
                }
            }
            bytes += row.toString().toByteArray().size
            require(bytes <= 8_000_000) { "Database result exceeds 8 MiB" }
            values += row
        }
        return Triple(columns, values, bytes)
    }

    private fun android(connection: DatabaseConnection.AndroidAppDatabase, directory: Path) {
        require(connection.serial.matches(Regex("[A-Za-z0-9._:-]+"))) { "Android serial contains unsupported characters" }
        require(connection.application.matches(Application) && connection.path.matches(RelativePath) && ".." !in connection.path)
        val backend = session.own(AdbDeviceBackend())
        runBlocking {
            val device = backend.session(DevelopmentDevice(connection.serial, connection.serial, DeviceTransport.Adb, "device")) as AdbDeviceSession
            val name = File(connection.path).name
            listOf("", "-wal", "-shm").forEach { suffix ->
                val remote = connection.path + suffix
                val present = device.shell("if run-as ${connection.application} test -r '$remote'; then echo present; fi").trim() == "present"
                require(present || suffix.isNotEmpty()) { "${connection.application} has no readable ${connection.path}; install a debuggable build" }
                if (present) device.exec("run-as ${connection.application} cat '$remote'", directory.resolve(name + suffix), MaxSnapshotBytes)
            }
            require(Files.size(directory.resolve(name)) > 0) { "Android database snapshot was empty" }
        }
    }

    private fun <T> snapshot(block: (Path) -> T): T {
        val directory = Files.createTempDirectory("reaktor-sqlite-snapshot", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        return try { block(directory) } finally {
            Files.list(directory).use { entries -> entries.forEach { runCatching { Files.deleteIfExists(it) } } }
            runCatching { Files.deleteIfExists(directory) }
        }
    }

    private fun requireOwnerOnly(file: File) {
        listOf(file, file.absoluteFile.parentFile).forEach { path ->
            require(!Files.isSymbolicLink(path.toPath())) { "SQLite database path cannot traverse symbolic links" }
            val permissions = runCatching { Files.getPosixFilePermissions(path.toPath()) }.getOrNull()
            require(permissions == null || permissions.none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) { "SQLite database permissions are not owner-only" }
        }
    }

    private companion object {
        val Application = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val RelativePath = Regex("[A-Za-z0-9._ /-]+")
        const val MaxSnapshotBytes = 512L * 1024 * 1024
    }
}
