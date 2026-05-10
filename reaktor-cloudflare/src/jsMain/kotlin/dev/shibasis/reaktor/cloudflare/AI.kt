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

    /**
     * Calls Llama Guard with a single user message and returns the raw classifier
     * output (e.g. "safe" or "unsafe\nS1,S3"). Llama Guard's chat template rejects
     * the `system` role, so we never send one.
     */
    suspend fun llamaGuardClassify(text: String): String {
        val userMsg = js("({})")
        userMsg.role = "user"
        userMsg.content = text

        val inputs = js("({})")
        inputs.messages = arrayOf(userMsg)

        val result = raw.run("@cf/meta/llama-guard-3-8b", inputs).await()
        return result.response?.toString()?.trim() ?: ""
    }

    // Caption / classify an image. Uses Workers AI vision models (LLaVA by default).
    // Returns the model's free-form description; callers parse it for moderation cues.
    suspend fun describeImage(
        bytes: ByteArray,
        prompt: String,
        model: String = "@cf/llava-hf/llava-1.5-7b-hf",
        maxTokens: Int = 64,
    ): String {
        val intArray = IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
        val inputs = js("({})")
        inputs.image = intArray
        inputs.prompt = prompt
        inputs.max_tokens = maxTokens

        val result = raw.run(model, inputs).await()
        return result.description?.toString()?.trim()
            ?: result.response?.toString()?.trim()
            ?: ""
    }
}
