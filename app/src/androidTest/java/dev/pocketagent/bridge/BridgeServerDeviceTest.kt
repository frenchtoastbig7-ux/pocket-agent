package dev.pocketagent.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fasterxml.jackson.databind.JsonNode
import dev.pocketagent.agent.AgentTool
import dev.pocketagent.agent.ApprovalDecision
import dev.pocketagent.agent.Risk
import dev.pocketagent.agent.Schema
import dev.pocketagent.agent.ToolGroup
import dev.pocketagent.agent.ToolOutput
import dev.pocketagent.agent.str
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Runs the bridge's MCP server on Android's runtime (Ktor CIO + MCP SDK on ART) and speaks raw MCP JSON-RPC to it.
 * The token here exists only inside this test.
 */
@RunWith(AndroidJUnit4::class)
class BridgeServerDeviceTest {
    private val token = "device-test-token"
    private val port = ServerSocket(0).use { it.localPort }
    private val approvals = mutableListOf<String>()
    private lateinit var server: BridgeServer

    private class EchoTool : AgentTool("echo", "Echoes text.", ToolGroup.DEVICE, Risk.READ) {
        override val properties = mapOf("text" to Schema.str("Text"))
        override val required = listOf("text")
        override suspend fun run(input: JsonNode) = ToolOutput("echo: ${input.str("text")}")
    }

    private inner class SendTool : AgentTool("send_thing", "Sends a thing.", ToolGroup.PERSONAL, Risk.EXTERNAL) {
        override val properties = mapOf("to" to Schema.str("Recipient"))
        override suspend fun run(input: JsonNode) = ToolOutput("sent")
    }

    @Before
    fun start() {
        server = BridgeServer(
            tools = listOf(EchoTool(), SendTool()),
            token = token,
            host = "127.0.0.1",
            port = port,
            approver = { tool, _ ->
                approvals += tool.name
                ApprovalDecision.DENY
            },
        )
        server.start()
    }

    @After
    fun stop() = server.stop()

    private class Response(val status: Int, val body: String, val session: String?)

    /**
     * Plain HTTP/1.1 over a socket. The app's cleartext policy blocks HttpURLConnection to http:// URLs, and this test
     * runs inside the app's process; the policy only governs the app's own outgoing requests, not the server.
     */
    private fun post(json: String, auth: String?, session: String? = null): Response {
        val payload = json.toByteArray()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            val head = buildString {
                append("POST /mcp HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                append("Content-Type: application/json\r\n")
                append("Accept: application/json, text/event-stream\r\n")
                auth?.let { append("Authorization: $it\r\n") }
                session?.let { append("Mcp-Session-Id: $it\r\n") }
                append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray())
                write(payload)
                flush()
            }
            val raw = ByteArrayOutputStream()
            try {
                socket.getInputStream().copyTo(raw)
            } catch (_: SocketTimeoutException) {
                // An SSE response can leave the stream open; everything we need has arrived by now.
            }
            val text = raw.toString("UTF-8")
            val split = text.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: text.length
            val headers = text.substring(0, split).split("\r\n")
            val status = headers.first().split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            val sessionHeader = headers.drop(1).firstOrNull { it.startsWith("Mcp-Session-Id:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
            val chunked = headers.any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) }
            val body = text.substring(minOf(split + 4, text.length)).let { if (chunked) dechunk(it) else it }
            return Response(status, body, sessionHeader)
        }
    }

    private fun dechunk(body: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val lineEnd = body.indexOf("\r\n", i).takeIf { it >= 0 } ?: break
            val size = body.substring(i, lineEnd).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            out.append(body, start, minOf(start + size, body.length))
            i = start + size + 2
        }
        return out.toString()
    }

    @Test
    fun authListCallAndGateOnDevice() {
        assertEquals(401, post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", auth = null).status)

        val bearer = "Bearer $token"
        val init = post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"device-test","version":"1"}}}""",
            bearer,
        )
        assertEquals("initialize body: ${init.body}", 200, init.status)
        val session = init.session
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", bearer, session)

        val list = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", bearer, session)
        assertTrue("tools/list: ${list.body}", list.body.contains("\"echo\"") && list.body.contains("\"send_thing\""))
        assertTrue("action tools carry the approval marker: ${list.body}", list.body.contains("anthropic/requiresUserInteraction"))

        val echo = post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""", bearer, session)
        assertTrue("echo: ${echo.body}", echo.body.contains("echo: hi"))

        val send = post("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"send_thing","arguments":{"to":"sam"}}}""", bearer, session)
        assertTrue("send should be denied: ${send.body}", send.body.contains("declined"))
        assertEquals(listOf("send_thing"), approvals)
    }
}
