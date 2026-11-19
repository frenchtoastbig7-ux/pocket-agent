package dev.pocketagent.agent

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exercises the real agent loop against the Claude API: streaming, a client tool round trip, the approval policy,
 * history replay on a second turn, and the request shape (fallbacks, caching, adaptive thinking).
 * Costs a few cents, so it only runs with POCKET_AGENT_LIVE_TEST=1 and API credentials in the environment.
 */
class AgentLoopLiveTest {

    private class CodeWordTool : AgentTool(
        name = "get_code_word",
        description = "Returns today's code word. Call this whenever the user asks for the code word.",
        group = ToolGroup.DEVICE,
        risk = Risk.READ,
    ) {
        override val properties = emptyMap<String, Map<String, Any>>()
        override suspend fun run(input: JsonNode) = ToolOutput("PERIWINKLE")
    }

    private class SaveNoteTool(val calls: MutableList<String>) : AgentTool(
        name = "save_note",
        description = "Saves a note for the user. Call this when the user asks you to save a note.",
        group = ToolGroup.FILES,
        risk = Risk.WRITE,
    ) {
        override val properties = mapOf("text" to Schema.str("Note text"))
        override val required = listOf("text")
        override suspend fun run(input: JsonNode): ToolOutput {
            calls += input.str("text").orEmpty()
            return ToolOutput("Saved.")
        }
    }

    @Test
    fun toolRoundTripApprovalAndReplay() {
        assumeTrue(System.getenv("POCKET_AGENT_LIVE_TEST") == "1")
        val client = AnthropicOkHttpClient.fromEnv()
        val saved = mutableListOf<String>()
        val approvals = mutableListOf<String>()
        val loop = AgentLoop(
            client = client,
            model = "claude-opus-5",
            effort = "low",
            systemPrompt = "You are a test assistant. Keep answers to one short sentence.",
            tools = listOf(CodeWordTool(), SaveNoteTool(saved)),
            webTools = { false },
            mode = RunMode.INTERACTIVE,
            approver = { tool, summary, _ ->
                approvals += "${tool.name}: $summary"
                ApprovalDecision.DENY
            },
        )

        val events = mutableListOf<AgentEvent>()
        val first = runBlocking { loop.send("What is the code word?") { events += it } }
        assertTrue("expected a tool call, got $events", events.any { it is AgentEvent.ToolCall && it.name == "get_code_word" })
        assertTrue("reply was: $first", first.contains("periwinkle", ignoreCase = true))
        assertTrue(events.any { it is AgentEvent.TextDelta })

        // Second turn replays history (thinking + tool_use + tool_result) and hits the approval gate.
        val second = runBlocking { loop.send("Save a note that says: buy milk") {} }
        assertEquals("the WRITE tool must go through the approver", 1, approvals.size)
        assertTrue("a denied tool must not run", saved.isEmpty())
        println("first=$first\nsecond=$second\napprovals=$approvals")
    }
}
