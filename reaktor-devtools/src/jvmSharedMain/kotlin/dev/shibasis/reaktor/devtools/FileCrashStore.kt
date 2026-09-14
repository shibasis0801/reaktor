package dev.shibasis.reaktor.devtools

import java.io.File

/**
 * Crash reports as files in one directory.
 *
 * Deliberately plain: this is written by a process that is already failing, so the fewer moving
 * parts between the report and the disk, the better the odds it survives. Every operation
 * swallows its own failure for the same reason — a crash handler that throws loses the crash.
 */
class FileCrashStore(private val directory: File) : CrashStore {

    override fun write(report: String) {
        runCatching {
            directory.mkdirs()
            File(directory, "crash-${System.currentTimeMillis()}.txt").writeText(report)
        }
    }

    override fun readAll(): List<String> = runCatching {
        directory.listFiles { file -> file.isFile && file.name.startsWith("crash-") }
            ?.sortedBy { it.name }
            ?.mapNotNull { file -> runCatching { file.readText() }.getOrNull() }
            .orEmpty()
    }.getOrElse { emptyList() }

    override fun clear() {
        runCatching {
            directory.listFiles { file -> file.name.startsWith("crash-") }?.forEach { it.delete() }
        }
    }
}
