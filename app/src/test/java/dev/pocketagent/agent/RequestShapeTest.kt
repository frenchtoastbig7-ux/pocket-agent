package dev.pocketagent.agent

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.fasterxml.jackson.databind.JsonNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline checks of what the agent loop puts on the wire. No network access. */
class RequestShapeTest {

    private class EchoTool : AgentTool("echo", "Echoes text back.", ToolGroup.DEVICE, Risk.READ) {
        override val properties = mapOf(
            "text" to Schema.str("Text to echo"),
            "mode" to Schema.str("How", listOf("plain", "loud")),
        )
        override val required = listOf("text")
        override suspend fun run(input: JsonNode) = ToolOutput(input.str("text").orEmpty())
    }

    private fun loop(webTools: Boolean) = AgentLoop(
        client = AnthropicOkHttpClient.builder().apiKey("sk-ant-test").build(),
        model = "claude-opus-5",
        effort = "xhigh",
        systemPrompt = "system prompt",
        tools = listOf(EchoTool()),
        webTools = webTools,
        mode = RunMode.INTERACTIVE,
        approver = null,
    )

    private fun body(loop: AgentLoop): JsonNode {
        loop.appendUserTextForTest("hello")
        val params = loop.buildParams()
        return JsonValue.from(params._body()).convert(JsonNode::class.java)!!
    }

    @Test
    fun requestCarriesModelThinkingEffortCachingAndFallbacks() {
        val loop = loop(webTools = true)
        val body = body(loop)
        assertEquals("claude-opus-5", body["model"].asText())
        assertEquals(64000, body["max_tokens"].asInt())
        assertEquals("adaptive", body["thinking"]["type"].asText())
        assertEquals("summarized", body["thinking"]["display"].asText())
        assertEquals("xhigh", body["output_config"]["effort"].asText())
        assertEquals("ephemeral", body["cache_control"]["type"].asText())
        assertEquals("default", body["fallbacks"].asText())
        assertEquals("system prompt", body["system"][0]["text"].asText())
        assertEquals("user", body["messages"][0]["role"].asText())

        val headers = loop.buildParams()._headers().values("anthropic-beta").joinToString(",")
        assertTrue("beta header was: $headers", headers.contains("server-side-fallback-2026-07-01"))
    }

    @Test
    fun toolsHaveSchemasAndWebToolsAreOptional() {
        val withWeb = body(loop(webTools = true))["tools"]
        val echo = withWeb.first { it["name"].asText() == "echo" }
        assertEquals("object", echo["input_schema"]["type"].asText())
        assertEquals("string", echo["input_schema"]["properties"]["text"]["type"].asText())
        assertEquals("loud", echo["input_schema"]["properties"]["mode"]["enum"][1].asText())
        assertEquals("text", echo["input_schema"]["required"][0].asText())
        val types = withWeb.map { it["type"]?.asText() }
        assertTrue(types.toString(), types.contains("web_search_20260209") && types.contains("web_fetch_20260209"))

        val withoutWeb = body(loop(webTools = false))["tools"]
        assertEquals(1, withoutWeb.size())
    }

    @Test
    fun toolInputConvertsToJsonNode() {
        val use = BetaToolUseBlock.builder()
            .id("toolu_1")
            .name("echo")
            .input(JsonValue.from(mapOf("text" to "hi", "count" to 3, "nested" to mapOf("ok" to true))))
            .build()
        val node = use._input().convert(JsonNode::class.java)!!
        assertEquals("hi", node.str("text"))
        assertEquals(3, node.int("count", 0))
        assertTrue(node["nested"]["ok"].asBoolean())
    }
}
