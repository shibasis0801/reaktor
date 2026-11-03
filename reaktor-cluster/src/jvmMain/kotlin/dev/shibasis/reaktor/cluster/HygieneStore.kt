package dev.shibasis.reaktor.cluster

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HygieneReading(val report: HygieneReport? = null, val truth: String, val problem: String? = null)

class HygieneStore(private val folder: Path, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    fun read(): HygieneReading {
        val path = folder.resolve("latest.json")
        if (!Files.exists(path)) return HygieneReading(truth = "Unknown", problem = "No hygiene report has been recorded")
        return try {
            require(!Files.isSymbolicLink(path) && Files.size(path) <= 4_194_304)
            val report = json.decodeFromString<HygieneReport>(Files.readString(path))
            HygieneReading(report, if (now() - report.observedAt > 300_000) "Stale" else report.truth)
        } catch (_: Exception) { HygieneReading(truth = "Failed", problem = "Stored hygiene report is unreadable") }
    }

    fun write(report: HygieneReport): Path {
        Files.createDirectories(folder)
        val text = json.encodeToString(report)
        val path = folder.resolve("report-${report.observedAt}-${report.digest.take(12)}.json")
        Files.writeString(path, text, java.nio.file.StandardOpenOption.CREATE_NEW)
        val temporary = Files.createTempFile(folder, "latest-", ".json")
        try {
            Files.writeString(temporary, text)
            Files.move(temporary, folder.resolve("latest.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return path
    }
}
