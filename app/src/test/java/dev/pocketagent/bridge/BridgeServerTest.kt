package dev.pocketagent.bridge

import com.fasterxml.jackson.databind.JsonNode
import dev.pocketagent.agent.AgentTool
import dev.pocketagent.agent.ApprovalDecision
import dev.pocketagent.agent.Risk
import dev.pocketagent.agent.Schema
import dev.pocketagent.agent.ToolGroup
import dev.pocketagent.agent.ToolOutput
import dev.pocketagent.agent.str
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/** Runs the real bridge server on loopback and talks to it with the MCP Kotlin client. */
class BridgeServerTest {
    private val token = "test-token-0123456789"
    private val port = ServerSocket(0).use { it.localPort }
    private val sent = mutableListOf<String>()
    private val approvals = mutableListOf<String>()
    private var nextDecision = ApprovalDecision.DENY
    private var now = 1_000_000L
    private val off = mutableSetOf<ToolGroup>()
    private lateinit var server: BridgeServer

    private class EchoTool : AgentTool("echo", "Echoes text.", ToolGroup.DEVICE, Risk.READ) {
        override val properties = mapOf("text" to Schema.str("Text"))
        override val required = listOf("text")
        override suspend fun run(input: JsonNode) = ToolOutput("echo: ${input.str("text")}")
    }

    private inner class SendTool : AgentTool("send_thing", "Sends a thing.", ToolGroup.PERSONAL, Risk.EXTERNAL) {
        override val properties = mapOf("to" to Schema.str("Recipient"))
        override val required = listOf("to")
        override fun summarize(input: JsonNode) = "Send to ${input.str("to")}"
        override suspend fun run(input: JsonNode): ToolOutput {
            sent += input.str("to").orEmpty()
            return ToolOutput("sent")
        }
    }

    @Before
    fun start() {
        server = BridgeServer(
            tools = listOf(EchoTool(), SendTool()),
            token = token,
            host = "127.0.0.1",
            port = port,
            approver = { tool, summary ->
                approvals += "${tool.name}|$summary"
                nextDecision
            },
            clock = { now },
            isEnabled = { it !in off },
        )
        server.start()
    }

    @After
    fun stop() = server.stop()

    /** A hand-written request over a socket, so the Host header is exactly what the test says (the JDK client rewrites it). */
    private fun rawStatus(authorization: String?, host: String = "127.0.0.1:$port", body: String = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val bytes = body.toByteArray()
            val head = buildString {
                append("POST /mcp HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\n")
                append("Accept: application/json, text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                authorization?.let { append("Authorization: $it\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
            // Status line: "HTTP/1.1 200 OK".
            socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }

    @Test
    fun rejectsMissingOrWrongToken() {
        assertEquals(401, rawStatus(null))
        assertEquals(401, rawStatus("Bearer wrong"))
        assertEquals(401, rawStatus(token))
        assertTrue("valid token should pass auth", rawStatus("Bearer $token") != 401)
    }

    @Test
    fun hostCheckAcceptsTheBoundAddressAndRejectsOthers() {
        val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
        assertEquals(200, rawStatus("Bearer $token", host = "127.0.0.1:$port", body = init))
        val foreign = rawStatus("Bearer $token", host = "evil.example:$port", body = init)
        assertTrue("a foreign Host header should be refused, got $foreign", foreign in 400..499)
    }

    @Test
    fun aCapabilitySwitchedOffOnThePhoneIsRefused() = runBlocking {
        val http = HttpClient(CIO) {
            install(SSE)
            defaultRequest { header("Authorization", "Bearer $token") }
        }
        val client = Client(Implementation(name = "test", version = "1.0"))
        client.connect(StreamableHttpClientTransport(http, "http://127.0.0.1:$port/mcp"))
        off += ToolGroup.DEVICE
        val refused = client.callTool("echo", mapOf("text" to "hi"))
        assertEquals(true, refused.isError)
        off.clear()
        assertEquals("echo: hi", (client.callTool("echo", mapOf("text" to "hi")).content.first() as TextContent).text)
        client.close()
        http.close()
    }

    @Test
    fun listsToolsRunsReadsAndGatesActions() = runBlocking {
        val http = HttpClient(CIO) {
            install(SSE)
            defaultRequest { header("Authorization", "Bearer $token") }
        }
        val client = Client(Implementation(name = "test", version = "1.0"))
        client.connect(StreamableHttpClientTransport(http, "http://127.0.0.1:$port/mcp"))

        val tools = client.listTools().tools
        assertEquals(setOf("echo", "send_thing"), tools.map { it.name }.toSet())

        val echo = client.callTool("echo", mapOf("text" to "hi"))
        assertEquals("echo: hi", (echo.content.first() as TextContent).text)
        assertTrue("reads need no approval", approvals.isEmpty())

        nextDecision = ApprovalDecision.DENY
        val denied = client.callTool("send_thing", mapOf("to" to "sam"))
        assertEquals(true, denied.isError)
        assertTrue(sent.isEmpty())
        assertEquals(listOf("send_thing|Send to sam"), approvals)

        nextDecision = ApprovalDecision.FOR_TASK
        client.callTool("send_thing", mapOf("to" to "sam"))
        client.callTool("send_thing", mapOf("to" to "alex"))
        assertEquals(listOf("sam", "alex"), sent)
        assertEquals("second call inside the window must not ask again", 2, approvals.size)

        now += 16 * 60_000L
        nextDecision = ApprovalDecision.DENY
        client.callTool("send_thing", mapOf("to" to "late"))
        assertEquals("window expired, so it asks again", 3, approvals.size)
        assertEquals(listOf("sam", "alex"), sent)

        client.close()
        http.close()
    }
}
