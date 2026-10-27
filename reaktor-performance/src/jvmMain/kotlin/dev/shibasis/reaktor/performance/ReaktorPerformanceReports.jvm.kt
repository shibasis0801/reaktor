package dev.shibasis.reaktor.performance

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import dev.shibasis.reaktor.core.framework.prettyJson

object ReaktorPerformanceReports {

    fun write(
        report: ReaktorPerformanceReport,
        path: Path,
    ) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, prettyJson.encodeToString(report))
    }

    fun read(path: Path): ReaktorPerformanceReport =
        prettyJson.decodeFromString(Files.readString(path))
}
