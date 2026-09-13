package dev.pocketagent

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import dev.pocketagent.agent.AgentEvent
import dev.pocketagent.agent.AgentLoop
import dev.pocketagent.agent.ApprovalDecision
import dev.pocketagent.agent.Approver
import dev.pocketagent.agent.RunMode
import dev.pocketagent.agent.ToolStatus
import dev.pocketagent.device.AgentAccessibilityService
import dev.pocketagent.device.AgentRunService
import dev.pocketagent.device.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

class PendingApproval(val title: String, val summary: String, val allowForTask: Boolean, val decision: CompletableDeferred<ApprovalDecision>)

/** The interactive chat: owns the current [AgentLoop], exposes UI state, and routes approvals to the screen or overlay. */
class ChatController(private val app: PocketAgentApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val items = MutableStateFlow<List<ChatItem>>(emptyList())
    val running = MutableStateFlow(false)
    val pending = MutableStateFlow<PendingApproval?>(null)
    val finishedReplies = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** True while MainActivity is resumed; otherwise approvals go to the accessibility overlay or a notification. */
    @Volatile
    var uiVisible = false

    private var loop: AgentLoop? = null
    private var sessionId: Long? = null
    private var job: Job? = null
    private var nextId = 0L
    private var currentAssistant: Long? = null
    private var currentThinking: Long? = null
    private val toolItems = HashMap<String, Long>()

    private val approver = Approver { tool, summary, allowForTask ->
        val decision = CompletableDeferred<ApprovalDecision>()
        val title = "Allow ${tool.name.replace('_', ' ')}?"
        withContext(Dispatchers.Main) {
            val overlay = AgentAccessibilityService.instance
            if (!uiVisible && overlay != null) {
                overlay.showApproval(title, summary, allowForTask) { decision.complete(it) }
            } else {
                pending.value = PendingApproval(title, summary, allowForTask, decision)
                if (!uiVisible) Notifications.approvalNeeded(app, title)
            }
        }
        try {
            decision.await()
        } finally {
            withContext(Dispatchers.Main) {
                pending.value = null
                AgentAccessibilityService.instance?.dismissApproval()
                Notifications.clearApproval(app)
            }
        }
    }

    fun resolveApproval(decision: ApprovalDecision) {
        pending.value?.decision?.complete(decision)
    }

    fun send(text: String) {
        val message = text.trim()
        if (running.value || message.isEmpty()) return
        val client = app.factory.client()
        if (client == null) {
            add(ChatItem.Kind.NOTICE, "Add your Anthropic API key in Settings first.")
            return
        }
        val loop = loop ?: app.factory.newLoop(client, RunMode.INTERACTIVE, approver) { sessionId }.also { loop = it }
        add(ChatItem.Kind.USER, message)
        currentAssistant = null
        currentThinking = null
        running.value = true
        AgentRunService.startInteractive(app)

        job = scope.launch {
            try {
                val sid = withContext(Dispatchers.IO) {
                    (sessionId ?: app.sessions.create("chat", message.take(60)).also { sessionId = it })
                        .also { app.sessions.addTurn(it, "user", message) }
                }
                val reply = loop.send(message) { event -> withContext(Dispatchers.Main) { handle(event) } }
                withContext(Dispatchers.IO) { app.sessions.addTurn(sid, "assistant", reply) }
                if (reply.isNotBlank()) finishedReplies.tryEmit(reply)
            } catch (_: CancellationException) {
                add(ChatItem.Kind.NOTICE, "Stopped.")
            } catch (e: Exception) {
                add(ChatItem.Kind.NOTICE, describeError(e))
            } finally {
                running.value = false
                pending.value = null
                AgentRunService.stopInteractive(app)
            }
        }
    }

    fun stop() {
        job?.cancel()
        pending.value?.decision?.complete(ApprovalDecision.DENY)
    }

    /** Ends the session. Memory written during it shows up in the next chat's prompt. */
    fun newChat() {
        stop()
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
