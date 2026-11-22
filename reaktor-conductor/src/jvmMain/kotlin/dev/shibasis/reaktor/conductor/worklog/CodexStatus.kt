package dev.shibasis.reaktor.conductor.worklog

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

internal data class CodexTurnBoundary(val type: String, val at: Long?, val offset: Long, val turnId: String? = null)

internal object CodexStatus {
    private data class BoundaryCursor(val key: Any?, val length: Long, val modified: java.nio.file.attribute.FileTime,
        val prefix: Int, val tail: Int, val scanned: Long, val boundary: CodexTurnBoundary?, val boundaryBytes: Int, val boundaryHash: Int)
    private val codexBoundaries = ConcurrentHashMap<String, BoundaryCursor>()
    private const val BoundaryBytes = 64 * 1024
    private val BoundaryType = Regex(""""payload"\s*:\s*\{\s*"type"\s*:\s*"(task_started|task_complete|turn_aborted)"""")
    private val BoundaryTime = Regex(""""timestamp"\s*:\s*"([^"]+)"""")
    private val BoundaryId = Regex("\"turn_id\"\\s*:\\s*\"([^\"]+)\"")
    private val BoundaryEvent = Regex(""""type"\s*:\s*"event_msg"""")

    private fun boundary(bytes: ByteArray, offset: Long, prefix: Boolean = false): CodexTurnBoundary? {
        if (listOf("task_started", "task_complete", "turn_aborted").none { (0..bytes.size - it.length).any { start -> it.indices.all { index -> bytes[start + index] == it[index].code.toByte() } } }) return null
        val text = bytes.decodeToString()
        val record = parseRecord(text)
        if (record != null) return of(record, offset)
        val type = if (prefix && BoundaryEvent.containsMatchIn(text)) BoundaryType.find(text)?.groupValues?.get(1) else null
        if (type !in setOf("task_started", "task_complete", "turn_aborted")) return null
        return CodexTurnBoundary(type!!, instant(BoundaryTime.find(text)?.groupValues?.get(1)), offset, BoundaryId.find(text)?.groupValues?.get(1))
    }

    @Synchronized fun read(file: File): CodexTurnBoundary? {
        val attributes = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        val prefix = RandomAccessFile(file, "r").use { input ->
            ByteArray(minOf(input.length(), 512).toInt()).also(input::readFully).let { bytes ->
                bytes.copyOfRange(0, bytes.indexOf(NewLine).takeIf { it >= 0 } ?: bytes.size).contentHashCode()
            }
        }
        fun tail(end: Long): Int = RandomAccessFile(file, "r").use { input ->
            val start = (end - 512).coerceAtLeast(0)
            input.seek(start)
            ByteArray((end - start).toInt()).also(input::readFully).contentHashCode()
        }
        fun boundaryHash(boundary: CodexTurnBoundary?, count: Int): Int = if (boundary == null) 0 else RandomAccessFile(file, "r").use { input ->
            input.seek(boundary.offset)
            ByteArray(count).also(input::readFully).contentHashCode()
        }
        val prior = codexBoundaries[file.path]?.takeIf {
            it.key == attributes.fileKey() && it.prefix == prefix && attributes.size() >= it.length &&
                (attributes.size() > it.length || attributes.lastModifiedTime() == it.modified) && tail(it.length) == it.tail &&
                boundaryHash(it.boundary, it.boundaryBytes) == it.boundaryHash
        }
        var found = prior?.boundary
        val scanned = if (prior != null) {
            lines(file, prior.scanned) { bytes, offset, truncated ->
                boundary(bytes, offset, truncated)?.let { found = it }
            }
        } else RandomAccessFile(file, "r").use { input ->
            var at = input.length()
            var end: Long? = null
            var appendAt = 0L
            fun record(start: Long, finish: Long) {
                val size = finish - start
                if (size <= 0) return
                val bytes = ByteArray(minOf(size, BoundaryBytes.toLong()).toInt())
                input.seek(start)
                input.readFully(bytes)
                boundary(bytes, start, size > BoundaryBytes)?.let { found = it }
            }
            while (at > 0 && found == null) {
                val start = (at - BoundaryBytes).coerceAtLeast(0)
                val bytes = ByteArray((at - start).toInt())
                input.seek(start)
                input.readFully(bytes)
                for (index in bytes.lastIndex downTo 0) {
                    if (bytes[index] != NewLine) continue
                    val position = start + index
                    val finish = end
                    if (finish == null) appendAt = position + 1 else record(position + 1, finish)
                    end = position
                    if (found != null) break
                }
                at = start
            }
            if (found == null) end?.let { record(0, it) }
            appendAt
        }
        if (codexBoundaries.size >= 2048) codexBoundaries.clear()
        val count = found?.let { value -> RandomAccessFile(file, "r").use { input ->
            input.seek(value.offset)
            val bytes = ByteArray(512)
            var count = 0
            while (count < BoundaryBytes) {
                val read = input.read(bytes, 0, minOf(bytes.size, BoundaryBytes - count))
                if (read <= 0) break
                val newline = (0 until read).firstOrNull { bytes[it] == NewLine }
                count += newline ?: read
                if (newline != null) break
            }
            count
        } } ?: 0
        codexBoundaries[file.path] = BoundaryCursor(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), prefix, tail(attributes.size()), scanned, found, count, boundaryHash(found, count))
        return found
    }


    private const val NewLine = 10.toByte()
    internal fun of(record: JsonObject, offset: Long = 0): CodexTurnBoundary? {
        if (record.str("type") != "event_msg") return null
        val type = record.obj("payload")?.str("type")?.takeIf { it in setOf("task_started", "task_complete", "turn_aborted") } ?: return null
        return CodexTurnBoundary(type, instant(record.str("timestamp")), offset, record.obj("payload")?.str("turn_id"))
    }
    private fun lines(file: File, from: Long, each: (ByteArray, Long, Boolean) -> Unit): Long = RandomAccessFile(file, "r").use { input ->
        input.seek(from)
        val buffer = ByteArray(BoundaryBytes)
        val head = ByteArray(BoundaryBytes)
        var size = 0
        var lineSize = 0L
        var consumed = from
        while (true) {
            val start = input.filePointer
            val count = input.read(buffer)
            if (count <= 0) break
            for (index in 0 until count) {
                if (buffer[index] == NewLine) {
                    each(head.copyOf(size), consumed, lineSize > size)
                    consumed = start + index + 1
                    size = 0
                    lineSize = 0
                } else {
                    if (size < head.size) head[size++] = buffer[index]
                    lineSize++
                }
            }
        }
        consumed
    }
}
