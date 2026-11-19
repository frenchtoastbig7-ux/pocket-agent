package dev.pocketagent

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import dev.pocketagent.agent.AgentEvent
import dev.pocketagent.agent.AgentLoop
import dev.pocketagent.agent.Approver
import dev.pocketagent.agent.RunMode
import dev.pocketagent.agent.ToolStatus
import dev.pocketagent.device.AgentRunService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatItem(
    val id: Long,
    val kind: Kind,
    val text: String,
    val status: ToolStatus? = null,
    val detail: String = "",
) {
    enum class Kind { USER, ASSISTANT, THINKING, TOOL, NOTICE }
}

/** The in-app chat (API-key mode): owns the current [AgentLoop] and exposes UI state. Approvals go through [ApprovalBroker]. */
class ChatController(private val app: PocketAgentApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val items = MutableStateFlow<List<ChatItem>>(emptyList())
    val running = MutableStateFlow(false)
    val finishedReplies = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var loop: AgentLoop? = null

    /** Read by session_search on an IO thread. */
    @Volatile
    private var sessionId: Long? = null
    private var job: Job? = null

    /** Bumped by [newChat]. A turn from an earlier chat checks it before touching the UI or the session. */
    private var epoch = 0
    private var nextId = 0L
    private var currentAssistant: Long? = null
    private var currentThinking: Long? = null
    private val toolItems = HashMap<String, Long>()

    private val approver = Approver { tool, summary, allowForTask ->
        app.approvals.request(
            title = "Allow ${tool.name.replace('_', ' ')}?",
            summary = summary,
            forTaskLabel = if (allowForTask) "Allow for task" else null,
        )
    }

    /** Starts a turn. Returns false when the message was not accepted, so the caller can keep the draft. */
    fun send(text: String): Boolean {
        val message = text.trim()
        if (running.value || message.isEmpty()) return false
        val client = app.factory.client()
        if (client == null) {
            add(ChatItem.Kind.NOTICE, "Add your Anthropic API key in Settings first, or use Claude Code through the bridge.")
            return false
        }
        val loop = loop ?: app.factory.newLoop(client, RunMode.INTERACTIVE, approver) { sessionId }.also { loop = it }
        // A key saved since the last turn takes effect without losing the chat.
        loop.client = client
        val startedIn = epoch
        val dataEpoch = app.dataEpoch
        val knownSession = sessionId
        add(ChatItem.Kind.USER, message)
        currentAssistant = null
        currentThinking = null
        running.value = true
        AgentRunService.startInteractive(app)

        job = scope.launch {
            fun current() = startedIn == epoch
            try {
                val sid = withContext(Dispatchers.IO) {
                    // Nothing is saved once "Wipe all agent data" has run, even if this block was already under way.
                    app.ifCurrent(dataEpoch) {
                        // The stored session may have been deleted on the Memory screen since the last turn.
                        (knownSession?.takeIf { app.sessions.exists(it) } ?: app.sessions.create("chat", message.take(60)))
                            .also { app.sessions.addTurn(it, "user", message) }
                    } ?: throw CancellationException("Data was wiped.")
                }
                if (current()) sessionId = sid
                val reply = loop.send(message) { event -> withContext(Dispatchers.Main) { if (current()) handle(event) } }
                withContext(Dispatchers.IO) { app.ifCurrent(dataEpoch) { app.sessions.addTurn(sid, "assistant", reply) } }
                if (current() && reply.isNotBlank()) finishedReplies.tryEmit(reply)
            } catch (_: CancellationException) {
                if (current()) add(ChatItem.Kind.NOTICE, "Stopped.")
            } catch (e: Exception) {
                // Stop interrupts a blocking read by closing the stream, which surfaces as an I/O error.
                if (current()) add(ChatItem.Kind.NOTICE, if (isActive) describeError(e) else "Stopped.")
            } finally {
                running.value = false
                AgentRunService.stopInteractive(app)
            }
        }
        return true
    }

    /** Cancelling the turn also cancels any approval it is waiting on. */
    fun stop() {
        job?.cancel()
    }

    /** Ends the session. Memory written during it shows up in the next chat's prompt. */
    fun newChat() {
        stop()
        epoch++
        loop = null
        sessionId = null
        toolItems.clear()
        items.value = emptyList()
    }

    private fun handle(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                currentThinking = null
                currentAssistant = appendTo(currentAssistant, ChatItem.Kind.ASSISTANT, event.text)
            }
            is AgentEvent.ThinkingDelta -> currentThinking = appendTo(currentThinking, ChatItem.Kind.THINKING, event.text)
            is AgentEvent.ToolCall -> {
                currentAssistant = null
                currentThinking = null
                toolItems[event.id] = add(ChatItem.Kind.TOOL, event.name, ToolStatus.RUNNING, event.summary)
            }
            is AgentEvent.ToolResult -> toolItems[event.id]?.let { id ->
                items.update { list -> list.map { if (it.id == id) it.copy(status = event.status) else it } }
            }
            is AgentEvent.WebActivity -> {
                currentAssistant = null
                add(ChatItem.Kind.TOOL, event.detail, ToolStatus.OK)
            }
            is AgentEvent.Notice -> add(ChatItem.Kind.NOTICE, event.text)
        }
    }

    private fun appendTo(id: Long?, kind: ChatItem.Kind, text: String): Long {
        if (id == null) return add(kind, text)
        items.update { list -> list.map { if (it.id == id) it.copy(text = it.text + text) else it } }
        return id
    }

    private fun add(kind: ChatItem.Kind, text: String, status: ToolStatus? = null, detail: String = ""): Long {
        val id = nextId++
        items.update { it + ChatItem(id, kind, text, status, detail) }
        return id
    }

    private fun describeError(e: Exception): String = when (e) {
        is UnauthorizedException -> "The API key was rejected. Check it in Settings."
        is RateLimitException -> "Rate limited by the API. Wait a moment and try again."
        is AnthropicServiceException -> "API error ${e.statusCode()}: ${e.message}"
        is AnthropicIoException -> "Network error: ${e.message}. Check that Pocket Agent has the Network permission."
        else -> "Error: ${e.message ?: e.javaClass.simpleName}"
    }
}
