package dev.shibasis.reaktor.tooling.database

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

enum class QueryExportFormat(val extension: String) { Csv("csv"), Json("json") }
data class QueryExportReceipt(val path: Path, val format: QueryExportFormat, val rows: Int,
    val bytes: Long, val sha256: String, val elapsedMillis: Long)

object QueryResultExport {
    fun write(destination: Path, columns: List<String>, rows: List<List<String?>>, format: QueryExportFormat,
        replaceExisting: Boolean = false): QueryExportReceipt {
        require(columns.size <= 512 && rows.size <= 500 && rows.all { it.size == columns.size })
        require((columns.asSequence() + rows.asSequence().flatten().filterNotNull()).sumOf { it.length.toLong() } <= 16_777_216) {
            "Result export exceeds the text budget"
        }
        val start = System.nanoTime()
        val data = when (format) {
            QueryExportFormat.Csv -> {
                fun quote(value: String?) = value?.let { "\"${it.replace("\"", "\"\"")}\"" } ?: "\\N"
                (listOf(columns) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::quote) }
            }
            QueryExportFormat.Json -> buildJsonObject {
                put("columns", JsonArray(columns.map(::JsonPrimitive)))
                put("rows", JsonArray(rows.map { row -> JsonArray(row.map { it?.let(::JsonPrimitive) ?: JsonNull }) }))
            }.toString()
        }.toByteArray(Charsets.UTF_8)
        require(data.size <= 16_777_216) { "Result export exceeds 16 MiB" }
        val path = destination.toAbsolutePath().normalize()
        require(Files.isDirectory(path.parent)) { "Choose an existing destination directory" }
        if (!replaceExisting && Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw java.nio.file.FileAlreadyExistsException(path.toString())
        val attributes = if (Files.getFileStore(path.parent).supportsFileAttributeView("posix"))
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
        val temporary = Files.createTempFile(path.parent, ".reaktor-export-", ".partial", *attributes)
        try {
            Files.write(temporary, data)
            if (replaceExisting) {
                try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
            } else Files.move(temporary, path)
        } finally { Files.deleteIfExists(temporary) }
        return QueryExportReceipt(path, format, rows.size, data.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(data).toHexString(), (System.nanoTime() - start) / 1_000_000)
    }
}
