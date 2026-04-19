package dev.shibasis.reaktor.cloudflare

import kotlinx.coroutines.await
import kotlin.js.Promise

internal external interface RawWorkersAI {
    fun run(model: String, inputs: dynamic): Promise<dynamic>
}

class WorkersAI internal constructor(private val raw: RawWorkersAI) {
    suspend fun textGeneration(
        model: String,
        systemPrompt: String,
        userMessage: String,
        temperature: Double = 0.7,
    ): String {
        val systemMsg = js("({})")
        systemMsg.role = "system"
        systemMsg.content = systemPrompt

        val userMsg = js("({})")
        userMsg.role = "user"
        userMsg.content = userMessage

        val inputs = js("({})")
        inputs.messages = arrayOf(systemMsg, userMsg)
        inputs.temperature = temperature

        val result = raw.run(model, inputs).await()
        return result.response?.toString() ?: ""
    }

    // Returns true if the text passes Llama Guard safety screening.
    // Uses @cf/meta/llama-guard-3-8b — responds "safe" or "unsafe\n<category>".
    suspend fun isSafe(text: String): Boolean {
        val userMsg = js("({})")
        userMsg.role = "user"
        userMsg.content = text

        val inputs = js("({})")
        inputs.messages = arrayOf(userMsg)

        val result = raw.run("@cf/meta/llama-guard-3-8b", inputs).await()
        return result.response?.toString()?.trim()?.startsWith("safe") ?: false
    }
}
