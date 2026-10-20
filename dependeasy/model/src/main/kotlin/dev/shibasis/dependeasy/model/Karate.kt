package dev.shibasis.dependeasy.model

object Karate {
    fun run(jar: String, arguments: List<String>, tags: List<String>, report: String, suites: List<String>): List<String> =
        listOf("-jar", jar, "run", "--runtime-log-level=warn", "--report-log-level=warn") +
            arguments + tags.flatMap { listOf("--tags", it) } + listOf("--output", report) + suites
}
