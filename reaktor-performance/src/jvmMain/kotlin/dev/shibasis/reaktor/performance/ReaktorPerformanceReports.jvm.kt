package dev.shibasis.reaktor.performance

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ReaktorPerformanceReports {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun write(
        report: ReaktorPerformanceReport,
        path: Path,
    ) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, json.encodeToString(report))
    }

    fun read(path: Path): ReaktorPerformanceReport =
        json.decodeFromString(Files.readString(path))
}
