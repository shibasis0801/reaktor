package dev.shibasis.dependeasy.model

object EnvironmentFiles {
    fun read(files: Set<java.io.File>): Map<String, String> = buildMap {
    files.forEach { file -> file.readLines().forEach { line ->
        val value = line.trim().removePrefix("export ")
        if (value.isBlank() || value.startsWith("#")) return@forEach
        val at = value.indexOf('=')
        require(at > 0) { "Invalid environment declaration in ${file.name}" }
        val key = value.substring(0, at).trim()
        require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid environment key in ${file.name}" }
        val raw = value.substring(at + 1).trim()
        put(key, if (raw.length >= 2 && raw.first() == raw.last() && raw.first() in listOf('\'', '"')) raw.substring(1, raw.length - 1) else raw)
    } }
}
}
