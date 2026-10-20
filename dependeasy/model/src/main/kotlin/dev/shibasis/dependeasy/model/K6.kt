package dev.shibasis.dependeasy.model

object K6 {
    fun run(summary: String, arguments: List<String>, script: String): List<String> =
        listOf("run", "--summary-export", summary) + arguments + script
}
