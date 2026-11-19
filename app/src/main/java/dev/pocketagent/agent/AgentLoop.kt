package dev.pocketagent.agent

import android.util.Base64
import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlock
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaServerToolUseBlock
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.jvm.optionals.getOrNull

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ThinkingDelta(val text: String) : AgentEvent
    data class ToolCall(val id: String, val name: String, val summary: String) : AgentEvent
    data class ToolResult(val id: String, val status: ToolStatus, val preview: String) : AgentEvent
    data class WebActivity(val detail: String) : AgentEvent
    data class Notice(val text: String) : AgentEvent
}

enum class ToolStatus { RUNNING, OK, ERROR, DENIED }

fun interface Approver {
    suspend fun approve(tool: AgentTool, summary: String, allowForTask: Boolean): ApprovalDecision
}

/**
 * The agent loop: streams a turn, runs client tools (asking the [Approver] where the risk policy says so), and
 * repeats until Claude ends its turn. History is append-only; a turn that can't be completed cleanly (refusal,
 * cancellation, error, length limit) is rolled back so the conversation is never left with an unanswered tool call.
 * The rollback stops at the last finished tool round: tools that already ran (a sent SMS, a written file) stay on
 * record, so Claude can't forget an action it took and repeat it.
 */
class AgentLoop(
    /** Replaceable so a chat keeps its history when the API key changes. */
    @Volatile var client: AnthropicClient,
    private val model: String,
    private val effort: String,
    private val systemPrompt: String,
    tools: List<AgentTool>,
    private val webTools: () -> Boolean,
    private val mode: RunMode,
    private val approver: Approver?,
    /** Asked before every request and every tool call, so switching a capability off takes effect in a running chat. */
    private val isEnabled: (ToolGroup) -> Boolean = { true },
) {
    private val history = mutableListOf<BetaMessageParam>()
    private val byName = tools.associateBy { it.name }
    private val allowedForTask = mutableSetOf<String>()
    private val mapper = ObjectMapper()

    // Sorted so the tool block of the prompt is byte-identical on every request and stays cached.
    private val apiTools: List<Pair<AgentTool, BetaTool>> = tools.sortedBy { it.name }.map { tool ->
        tool to BetaTool.builder()
            .name(tool.name)
            .description(tool.description)
            .inputSchema(
                BetaTool.InputSchema.builder()
                    .properties(
                        BetaTool.InputSchema.Properties.builder()
                            .apply { tool.properties.forEach { (key, schema) -> putAdditionalProperty(key, JsonValue.from(schema)) } }
                            .build()
                    )
                    .required(tool.required)
                    .build()
            )
            .build()
    }

    /** Sends one user message and runs the agent until it finishes. Returns the final reply text. */
    suspend fun send(text: String, images: List<ByteArray> = emptyList(), emit: suspend (AgentEvent) -> Unit): String =
        withContext(Dispatchers.IO) {
            // Where a failed turn rolls back to. Moves forward past each tool round that has run.
            var mark = history.size
            var acted = false
            val blocks = images.map { BetaContentBlockParam.ofImage(jpegBlock(it)) } + BetaContentBlockParam.ofText(text)
            history += userMessage(blocks)
            allowedForTask.clear()
            try {
                for (step in 0 until MAX_STEPS) {
                    ensureActive()
                    val message = streamOnce(emit)
                    val content = echoable(message.content())
                    val reply = content.mapNotNull { it.text().getOrNull()?.text() }.joinToString("")
                    when (message.stopReason().getOrNull()) {
                        BetaStopReason.TOOL_USE -> {
                            history += assistantMessage(content)
                            val uses = content.mapNotNull { it.toolUse().getOrNull() }
                            val results = mutableListOf<BetaContentBlockParam>()
                            try {
                                runTools(uses, emit, results)
                            } catch (t: Throwable) {
                                // Stopped partway through the round (a Stop while a later action waited for approval,
                                // say). What already ran stays on record; the calls that got no answer are closed so
                                // the conversation is still valid for the next request.
                                if (results.isNotEmpty()) {
                                    uses.drop(results.size).forEach {
                                        results += BetaContentBlockParam.ofToolResult(
                                            toolResult(it.id(), ToolOutput.error("The turn was stopped before this action finished. It may not have run."))
                                        )
                                    }
                                    history += userMessage(results)
                                    mark = history.size
                                }
                                throw t
                            }
                            history += userMessage(results)
                            mark = history.size
                            acted = true
                        }
                        BetaStopReason.PAUSE_TURN -> history += assistantMessage(content)
                        BetaStopReason.REFUSAL -> {
                            rollback(mark)
                            val category = message.stopDetails().getOrNull()?.category()?.getOrNull()?.toString()
                            emit(AgentEvent.Notice("Claude declined this request${category?.let { " ($it)" }.orEmpty()}. ${removed(acted)}"))
                            return@withContext ""
                        }
                        BetaStopReason.MAX_TOKENS -> {
                            rollback(mark)
                            emit(AgentEvent.Notice("The reply hit the length limit. ${removed(acted)} Try asking for less at once."))
                            return@withContext reply
                        }
                        BetaStopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> {
                            rollback(mark)
                            emit(AgentEvent.Notice("This chat is too long. Start a new chat; memory and past-chat search carry context over."))
                            return@withContext ""
                        }
                        else -> {
                            history += assistantMessage(content)
                            return@withContext reply
                        }
                    }
                }
                rollback(mark)
                emit(AgentEvent.Notice("Stopped after $MAX_STEPS steps without finishing."))
                ""
            } catch (t: Throwable) {
                rollback(mark)
                throw t
            }
        }

    private fun removed(acted: Boolean): String =
        if (acted) "The unfinished reply was removed; the actions already taken stay in the conversation."
        else "It was removed from the conversation."

    private fun rollback(mark: Int) {
        while (history.size > mark) history.removeAt(history.lastIndex)
    }

    /** The request for the next turn. Internal so tests can check its wire shape without network access. */
    internal fun buildParams(): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .systemOfBetaTextBlockParams(listOf(BetaTextBlockParam.builder().text(systemPrompt).build()))
            .messages(history.toList())
            .thinking(BetaThinkingConfigAdaptive.builder().display(BetaThinkingConfigAdaptive.Display.SUMMARIZED).build())
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(effort)).build())
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            // Server-side refusal fallbacks: if the model declines, the API continues on a fallback model in the same call.
            .addBeta(FALLBACK_BETA)
            .fallbacksDefault()
            .apply {
                apiTools.forEach { (tool, api) -> if (isEnabled(tool.group)) addTool(api) }
                if (webTools()) {
                    if (mode == RunMode.INTERACTIVE) {
                        addTool(BetaWebSearchTool20260209.builder().build())
                        addTool(BetaWebFetchTool20260209.builder().build())
                    } else {
                        // Server tools run inside the API call and never reach the approver. With nobody watching,
                        // text the run reads (an SMS, a notification) could ask it to fetch a URL that carries
                        // private data away, so these runs get a few searches and no fetch. The searches are called
                        // directly (not from the code-execution sandbox) so the limit counts across the whole run,
                        // not afresh on each request.
                        addTool(
                            BetaWebSearchTool20260209.builder()
                                .maxUses(UNATTENDED_SEARCHES)
                                .addAllowedCaller(BetaWebSearchTool20260209.AllowedCaller.DIRECT)
                                .build()
                        )
                    }
                }
            }
            .build()

    /** Test hook: seeds the conversation as if [text] had been sent. */
    internal fun appendUserTextForTest(text: String) {
        history += userMessage(listOf(BetaContentBlockParam.ofText(text)))
    }

    private suspend fun streamOnce(emit: suspend (AgentEvent) -> Unit): BetaMessage {
        val params = buildParams()
        val accumulator = BetaMessageAccumulator.create()
        val response = client.beta().messages().createStreaming(params)
        coroutineScope {
            // Closing the response is the only way to interrupt the blocking read when the user taps Stop.
            // Undispatched, so the watcher is already waiting before the first blocking read.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    response.close()
                }
            }
            try {
                response.use { stream ->
                    val events = stream.stream().iterator()
                    while (events.hasNext()) {
                        val event = events.next()
                        accumulator.accumulate(event)
                        event.contentBlockStart().getOrNull()?.contentBlock()?.let { block ->
                            block.serverToolUse().getOrNull()?.let { emit(AgentEvent.WebActivity(describe(it))) }
                            block.fallback().getOrNull()?.let {
                                emit(AgentEvent.Notice("The first model declined; continuing on ${it.to().model()}."))
                            }
                        }
                        event.contentBlockDelta().getOrNull()?.delta()?.let { delta ->
                            delta.text().getOrNull()?.let { emit(AgentEvent.TextDelta(it.text())) }
                            delta.thinking().getOrNull()?.let { emit(AgentEvent.ThinkingDelta(it.thinking())) }
                        }
                    }
                }
            } finally {
                closer.cancel()
            }
        }
        return accumulator.message()
    }

    /**
     * After a mid-output fallback, the API asks callers to drop non-text blocks produced before the last switch point
     * (thinking, tool calls, unpaired server tool calls). Tool calls from the declined attempt are never executed.
     */
    private fun echoable(blocks: List<BetaContentBlock>): List<BetaContentBlock> {
        val lastFallback = blocks.indexOfLast { it.isFallback() }
        if (lastFallback < 0) return blocks
        val answeredServerCalls = blocks.filter { !it.isToolUse() }.mapNotNull { it.toolUseId().getOrNull() }.toSet()
        return blocks.filterIndexed { index, block ->
            when {
                index > lastFallback -> true
                index == lastFallback -> false
                block.isText() -> true
                block.isServerToolUse() -> block.serverToolUse().get().id() in answeredServerCalls
                block.toolUseId().isPresent && !block.isToolUse() -> true
                else -> false
            }
        }
    }

    /** Appends each call's result to [results] as it finishes, so the caller still has them if a later call is cancelled. */
    private suspend fun runTools(uses: List<BetaToolUseBlock>, emit: suspend (AgentEvent) -> Unit, results: MutableList<BetaContentBlockParam>) {
        for (use in uses) {
            val tool = byName[use.name()]
            val input: JsonNode = runCatching { use._input().convert(JsonNode::class.java) }.getOrNull() ?: mapper.createObjectNode()
            val (output, status) = if (tool == null) {
                ToolOutput.error("Unknown tool ${use.name()}.") to ToolStatus.ERROR
            } else if (!isEnabled(tool.group)) {
                emit(AgentEvent.ToolCall(use.id(), tool.name, tool.name))
                ToolOutput.error("${tool.group.label} was switched off in Settings. Tell the user; don't retry.") to ToolStatus.DENIED
            } else {
                val summary = runCatching { tool.summarize(input) }.getOrDefault(tool.name)
                emit(AgentEvent.ToolCall(use.id(), tool.name, summary))
                val decision = decide(tool, summary)
                if (decision == ApprovalDecision.DENY) {
                    ToolOutput.error(denial()) to ToolStatus.DENIED
                } else if (!isEnabled(tool.group)) {
                    // Switched off while the approval prompt was up.
                    ToolOutput.error("${tool.group.label} was switched off in Settings. Tell the user; don't retry.") to ToolStatus.DENIED
                } else {
                    if (decision == ApprovalDecision.FOR_TASK) allowedForTask += tool.name
                    val result = try {
                        tool.run(input)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        ToolOutput.error("${tool.name} failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                    result to if (result.isError) ToolStatus.ERROR else ToolStatus.OK
                }
            }
            emit(AgentEvent.ToolResult(use.id(), status, output.text.take(400)))
            results += BetaContentBlockParam.ofToolResult(toolResult(use.id(), output))
        }
    }

    private suspend fun decide(tool: AgentTool, summary: String): ApprovalDecision = when {
        tool.risk == Risk.READ -> ApprovalDecision.ONCE
        mode != RunMode.INTERACTIVE -> ApprovalDecision.DENY
        tool.risk == Risk.SELF -> ApprovalDecision.ONCE
        !tool.alwaysConfirm && tool.name in allowedForTask -> ApprovalDecision.ONCE
        approver == null -> ApprovalDecision.DENY
        else -> approver.approve(tool, summary, allowForTask = !tool.alwaysConfirm)
    }

    private fun denial(): String = if (mode == RunMode.INTERACTIVE) {
        "The user declined this action. Don't retry it; ask what they would like instead."
    } else {
        "Not run: nobody is present to approve actions in this run. Describe what you would do instead."
    }

    private fun toolResult(id: String, output: ToolOutput): BetaToolResultBlockParam {
        val blocks = buildList {
            add(BetaToolResultBlockParam.Content.Block.ofText(output.text.ifEmpty { "(no output)" }))
            output.imageJpeg?.let { add(BetaToolResultBlockParam.Content.Block.ofImage(jpegBlock(it))) }
        }
        return BetaToolResultBlockParam.builder().toolUseId(id).contentOfBlocks(blocks).isError(output.isError).build()
    }

    private fun describe(block: BetaServerToolUseBlock): String = when (block.name()) {
        BetaServerToolUseBlock.Name.WEB_SEARCH -> "Searching the web"
        BetaServerToolUseBlock.Name.WEB_FETCH -> "Reading a web page"
        else -> "Using ${block.name()}"
    }

    private fun jpegBlock(bytes: ByteArray): BetaImageBlockParam = BetaImageBlockParam.builder()
        .source(
            BetaBase64ImageSource.builder()
                .mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG)
                .data(Base64.encodeToString(bytes, Base64.NO_WRAP))
                .build()
        )
        .build()

    private fun userMessage(blocks: List<BetaContentBlockParam>) =
        BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(blocks).build()

    private fun assistantMessage(blocks: List<BetaContentBlock>) =
        BetaMessageParam.builder().role(BetaMessageParam.Role.ASSISTANT).contentOfBetaContentBlockParams(blocks.map { it.toParam() }).build()

    private companion object {
        const val MAX_STEPS = 80
        const val MAX_TOKENS = 64_000L
        const val UNATTENDED_SEARCHES = 5L
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
