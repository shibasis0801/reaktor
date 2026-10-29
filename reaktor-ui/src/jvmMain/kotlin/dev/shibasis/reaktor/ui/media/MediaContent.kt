package dev.shibasis.reaktor.ui.media

import java.net.URI

sealed interface MediaSource {
    data class Bytes(val bytes: ByteArray) : MediaSource
    data class Url(val url: String) : MediaSource {
        init {
            val parsed = URI(url)
            require(parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()) { "Expected an HTTP or HTTPS media URL" }
        }
    }
}

internal enum class MediaKind { Image, Audio, Video, Text, Json, Csv, Pdf, Binary, Unsupported }

internal fun mediaKind(contentType: String): MediaKind = when (val mime = contentType.substringBefore(';').trim().lowercase()) {
    "application/json" -> MediaKind.Json
    "text/csv" -> MediaKind.Csv
    "application/pdf" -> MediaKind.Pdf
    "application/octet-stream" -> MediaKind.Binary
    "application/javascript", "application/typescript", "application/xml", "application/sql", "application/x-sh", "application/x-yaml" -> MediaKind.Text
    else -> when {
        mime.endsWith("+json") -> MediaKind.Json
        mime.startsWith("image/") -> MediaKind.Image
        mime.startsWith("audio/") -> MediaKind.Audio
        mime.startsWith("video/") -> MediaKind.Video
        mime.startsWith("text/") -> MediaKind.Text
        else -> MediaKind.Unsupported
    }
}

internal fun csvRecords(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var closed = false
    var started = false
    var index = 0
    fun cell() { row += field.toString(); field.clear(); closed = false }
    fun record() { cell(); rows += row; row = mutableListOf(); started = false }
    while (index < text.length) {
        val char = text[index++]
        if (quoted) {
            if (char == '"') {
                if (text.getOrNull(index) == '"') { field.append('"'); index++ }
                else { quoted = false; closed = true }
            } else field.append(char)
        } else when (char) {
            ',' -> { cell(); started = true }
            '\r', '\n' -> { if (char == '\r' && text.getOrNull(index) == '\n') index++; record() }
            '"' -> { require(field.isEmpty() && !closed) { "Unexpected quote in CSV field" }; quoted = true; started = true }
            else -> { require(!closed) { "Unexpected text after quoted CSV field" }; field.append(char); started = true }
        }
    }
    require(!quoted) { "Unclosed quoted CSV field" }
    if (started || field.isNotEmpty() || row.isNotEmpty()) record()
    return rows
}

internal fun mediaHex(bytes: ByteArray): String = bytes.asList().chunked(16).mapIndexed { index, row ->
    val hex = row.joinToString(" ") { (it.toInt() and 255).toString(16).padStart(2, '0') }.padEnd(47)
    val chars = row.joinToString("") { if (it.toInt() in 32..126) it.toInt().toChar().toString() else "." }
    "${(index * 16).toString(16).padStart(8, '0')}  $hex  $chars"
}.joinToString("\n")

internal fun mediaHtml(kind: MediaKind, source: String): String {
    val escaped = source.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    val body = when (kind) {
        MediaKind.Audio -> "<audio controls preload=metadata src=\"$escaped\">Audio playback is unavailable.</audio>"
        MediaKind.Video -> "<video controls preload=metadata style=\"max-width:100%;max-height:100%\" src=\"$escaped\">Video playback is unavailable.</video>"
        MediaKind.Pdf -> "<object type=\"application/pdf\" data=\"$escaped\" width=\"100%\" height=\"100%\"><a href=\"$escaped\">Open PDF pages</a></object>"
        else -> error("No native media view for $kind")
    }
    return "<!doctype html><html><head><meta name=viewport content=\"width=device-width,initial-scale=1\"></head><body style=\"margin:0;height:100vh;display:flex;align-items:center;justify-content:center\">$body</body></html>"
}
