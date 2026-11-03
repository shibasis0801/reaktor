package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentLabelTest {
    @Test fun theModelsFamilyComesFirstAndTheHarnessStaysOnTheProfile() {
        val architect = AgentProfile("architect", "Architect", RuntimeKind.ClaudeCode, model = "deepseek-v4", effort = "max")
        assertEquals("(deepseek, max, v4, architect)", AgentLabel.of(architect))
        assertEquals(ModelFamily.DeepSeek, ModelFamily.of(architect))
        assertEquals(RuntimeKind.ClaudeCode, architect.runtime)
    }

    @Test fun theModelReadsAsItsNameAndVersion() {
        assertEquals("opus 5.5", AgentLabel.model("claude-opus-5-5"))
        assertEquals("opus 5.5", AgentLabel.model("us.anthropic.claude-opus-5-5"))
        assertEquals("sonnet 5.5", AgentLabel.model("claude-sonnet-5-5-20261001"))
        assertEquals("sol 6.1", AgentLabel.model("gpt-6.1-sol"))
        assertEquals("gpt 6", AgentLabel.model("openai/gpt-6"))
        assertEquals("pro 3", AgentLabel.model("gemini-3-pro"))
        assertNull(AgentLabel.model(null))
        assertEquals(AgentTuple("claude", "max", "opus 5.5", "blueprint lead"),
            AgentLabel.tuple(AgentProfile("bpo-lead", "Blueprint lead", RuntimeKind.ClaudeCode, model = "claude-opus-5-5", effort = "max")))
        assertEquals("blueprint lead", AgentLabel.name("(claude, max, opus 5.5, blueprint lead)"))
        assertEquals("engine", AgentLabel.name("(codex, xhigh, engine)"))
    }

    @Test fun teamTuplesNameTheModelAndNativeTuplesAreUnchanged() {
        assertEquals("(claude, max, opus 5.5, agents lead)", AgentLabel.of(AgentProfile("bpo-agents-lead", "Agents lead", RuntimeKind.ClaudeCode, model = "claude-opus-5-5", effort = "max")))
        assertEquals("(codex, xhigh, sol 6.1, engine)", AgentLabel.of(AgentProfile("bpo-engine", "Engine", RuntimeKind.Codex, model = "gpt-6.1-sol", effort = "xhigh")))
        assertEquals("(codex, xhigh, configured model, falcon)", AgentLabel.of(AgentProfile("falcon", "Falcon", RuntimeKind.Codex, model = "configured-model", effort = "xhigh")))
        assertEquals("(codex)", AgentLabel.of(AgentProfile("native-codex", "Codex", RuntimeKind.Codex)))
        assertEquals("(claude)", AgentLabel.of(RuntimeKind.ClaudeCode, null, null))
        assertEquals("(chatgpt+gemini)", AgentLabel.of(RuntimeKind.ChatGptGemini, null, null))
        assertEquals("(agent)", AgentLabel.of(null, null, null))
    }

    @Test fun theModelDecidesBeforeTheHarnessEvenThroughAVendorPath() {
        assertEquals(ModelFamily.Codex, ModelFamily.of(RuntimeKind.ClaudeCode, "gpt-6.1-sol"))
        assertEquals(ModelFamily.Gemini, ModelFamily.of(RuntimeKind.Codex, "gemini-3-pro"))
        assertEquals(ModelFamily.Claude, ModelFamily.of(RuntimeKind.Gateway, "us.anthropic.claude-opus-5-5"))
        assertEquals(ModelFamily.Codex, ModelFamily.of(RuntimeKind.Gateway, "openai/gpt-6"))
        assertEquals(ModelFamily.DeepSeek, ModelFamily.of(RuntimeKind.Gateway, "deepseek-ai/DeepSeek-V4"))
        assertEquals(ModelFamily.Gemini, ModelFamily.of(RuntimeKind.Gemini, null))
        assertNull(ModelFamily.of(RuntimeKind.Echo, null))
        assertNull(ModelFamily.of(RuntimeKind.Gateway, "configured"))
    }
}
