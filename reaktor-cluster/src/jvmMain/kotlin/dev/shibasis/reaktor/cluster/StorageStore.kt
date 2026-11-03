package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StorageReading(val report: StorageReport? = null, val truth: String, val problem: String? = null)

class StorageStore(private val folder: Path, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun read(): StorageReading {
        val path = folder.resolve("latest.json")
        if (Files.notExists(path, NOFOLLOW_LINKS)) return StorageReading(truth = "Unknown", problem = "No Kotlin storage dry-run has been recorded")
        return try {
            require(!Files.isSymbolicLink(path) && Files.size(path) <= 8_388_608)
            val report = json.decodeFromString<StorageReport>(Files.readString(path))
            require(report.observedAt <= now() + 60_000)
            StorageReading(report, if (now() - report.observedAt > 300_000) "Stale" else report.truth)
        } catch (_: Exception) { StorageReading(truth = "Failed", problem = "Stored storage report is unreadable") }
    }

    fun write(report: StorageReport): Path {
        require(!Files.isSymbolicLink(folder))
        Files.createDirectories(folder)
        val text = json.encodeToString(report)
        require(text.toByteArray().size <= 8_388_608)
        val path = folder.resolve("report-${report.observedAt}.json")
        Files.writeString(path, text, java.nio.file.StandardOpenOption.CREATE_NEW)
        val temp = Files.createTempFile(folder, "latest-", ".json")
        try {
            Files.writeString(temp, text)
            Files.move(temp, folder.resolve("latest.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        return path
    }
}
