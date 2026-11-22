package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.core.framework.lenientJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ClaudeApp(
    private val home: File = File(System.getProperty("user.home")),
    private val bundle: File = listOf(File("/Applications/Claude.app"), File(home, "Applications/Claude.app")).firstOrNull(File::isDirectory)
        ?: File("/Applications/Claude.app"),
    private val launch: (String) -> Unit = ::openInBackground,
) {
    data class Session(val id: String, val cli: String, val directory: String, val createdAt: Long, val title: String?)

    val installed: Boolean get() = bundle.isDirectory

    private val store: File get() = File(home, "Library/Application Support/Claude/claude-code-sessions")

    fun sessions(since: Long = 0L): List<Session> = store.walkTopDown().maxDepth(3)
        .filter { it.isFile && it.name.startsWith("local_") && it.extension == "json" && it.lastModified() >= since }
        .mapNotNull(::session).toList()

    fun session(cli: String): Session? = sessions().firstOrNull { it.cli == cli }

    fun start(directory: File, prompt: String) = launch("claude://code/new?" + query("q" to prompt, "folder" to directory.canonicalPath, "source" to "reaktor"))

    fun show(session: Session) = launch("claude://code/continue?session=" + encode(session.id))

    fun adopt(cli: String) = launch("claude://resume?session=" + encode(cli))

    fun transcript(cli: String): File? = File(home, ".claude/projects").listFiles().orEmpty().map { File(it, "$cli.jsonl") }.firstOrNull(File::isFile)

    fun status(cli: String): String? = File(home, ".claude/sessions").listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
        .mapNotNull(::read).filter { it.text("sessionId") == cli }.maxByOrNull { it.number("statusUpdatedAt") ?: it.number("updatedAt") ?: 0L }?.text("status")

    private fun session(file: File): Session? {
        val row = read(file) ?: return null
        return Session(row.text("sessionId") ?: return null, row.text("cliSessionId") ?: return null, row.text("cwd") ?: return null,
            row.number("createdAt") ?: file.lastModified(), row.text("title"))
    }

    private fun read(file: File): JsonObject? = runCatching { lenientJson.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    companion object {
        const val PromptLimit = 14_000

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        private fun query(vararg pairs: Pair<String, String>): String = pairs.joinToString("&") { (key, value) -> "$key=${encode(value)}" }

        private fun openInBackground(url: String) {
            ProcessBuilder("open", "-g", url).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
        }
    }
}
