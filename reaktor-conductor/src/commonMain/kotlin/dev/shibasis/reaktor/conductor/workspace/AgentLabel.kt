package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind

enum class ModelFamily(val word: String, private val marks: List<String>) {
    Claude("claude", listOf("claude", "anthropic")),
    Codex("codex", listOf("gpt", "codex", "openai")),
    Gemini("gemini", listOf("gemini")),
    DeepSeek("deepseek", listOf("deepseek"));

    fun marked(word: String) = marks.any { word.startsWith(it) }

    companion object {
        private val byPriority = listOf(DeepSeek, Gemini, Claude, Codex)

        internal fun words(model: String?) = model.orEmpty().lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter(String::isNotEmpty)

        fun of(runtime: RuntimeKind?, model: String?): ModelFamily? {
            val words = words(model)
            return byPriority.firstOrNull { family -> words.any(family::marked) } ?: when (runtime) {
                RuntimeKind.ClaudeCode -> Claude
                RuntimeKind.Codex -> Codex
                RuntimeKind.Gemini -> Gemini
                else -> null
            }
        }

        fun of(profile: AgentProfile): ModelFamily? = of(profile.runtime, profile.model)
    }
}

data class AgentTuple(val family: String?, val effort: String? = null, val model: String? = null, val role: String? = null) {
    val label: String get() = listOfNotNull(family, effort, model, role).ifEmpty { listOf("agent") }.joinToString(", ", "(", ")")
}

object AgentLabel {
    fun tuple(runtime: RuntimeKind?, effort: String?, name: String?, model: String? = null) =
        AgentTuple(ModelFamily.of(runtime, model)?.word ?: runtime?.word, effort.word(), model(model), name.word())

    fun tuple(profile: AgentProfile) = tuple(profile.runtime, profile.effort, profile.name.takeUnless { profile.id.startsWith("native-") }, profile.model)

    fun of(runtime: RuntimeKind?, effort: String?, name: String?, model: String? = null): String = tuple(runtime, effort, name, model).label

    fun of(profile: AgentProfile): String = tuple(profile).label

    fun model(id: String?): String? {
        val words = ModelFamily.words(id).filterNot { it.length >= 6 && it.all(Char::isDigit) }
        val marks = words.filter { word -> ModelFamily.entries.any { it.marked(word) } }
        val rest = words.drop(words.indexOfFirst { it in marks } + 1).filterNot { it in marks }
        val names = rest.filterNot { word -> word.all(Char::isDigit) }.ifEmpty { listOfNotNull(marks.lastOrNull()) }
        val version = rest.filter { word -> word.all(Char::isDigit) }.joinToString(".")
        return (names + version).filter(String::isNotEmpty).joinToString(" ").ifEmpty { null }
    }

    fun runtime(session: String?): RuntimeKind? = when (session?.substringBefore(':')) {
        "claude" -> RuntimeKind.ClaudeCode
        "codex" -> RuntimeKind.Codex
        "agy" -> RuntimeKind.Gemini
        else -> null
    }

    fun labelled(text: String): Boolean = text.length > 2 && text.first() == '(' && text.last() == ')'

    fun name(text: String): String = if (labelled(text)) text.substring(1, text.length - 1).substringAfterLast(", ") else text

    private val RuntimeKind.word: String get() = when (this) {
        RuntimeKind.ClaudeCode -> "claude"
        RuntimeKind.Codex -> "codex"
        RuntimeKind.Gemini -> "gemini"
        RuntimeKind.ChatGptGemini -> "chatgpt+gemini"
        RuntimeKind.Gateway -> "gateway"
        RuntimeKind.Echo -> "echo"
    }

    private fun String?.word(): String? = this?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
}
