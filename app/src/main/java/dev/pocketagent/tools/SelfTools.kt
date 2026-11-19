package dev.pocketagent.tools

import com.fasterxml.jackson.databind.JsonNode
import dev.pocketagent.agent.AgentTool
import dev.pocketagent.agent.Risk
import dev.pocketagent.agent.Schema
import dev.pocketagent.agent.ToolGroup
import dev.pocketagent.agent.ToolOutput
import dev.pocketagent.agent.int
import dev.pocketagent.agent.str
import dev.pocketagent.memory.MemoryStore
import dev.pocketagent.memory.SessionStore
import dev.pocketagent.memory.SkillStore
import dev.pocketagent.schedule.ScheduleSpec
import dev.pocketagent.schedule.ScheduleStore
import dev.pocketagent.schedule.ScheduledTask
import dev.pocketagent.schedule.Scheduler
import dev.pocketagent.util.Times
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private inline fun attempt(block: () -> String): ToolOutput =
    runCatching { ToolOutput(block()) }.getOrElse { ToolOutput.error(it.message ?: it.toString()) }

class MemoryTool(private val store: MemoryStore) : AgentTool(
    name = "memory",
    description = "Save, update or remove durable notes that are loaded into every future chat. Use target \"user\" " +
        "for who the user is and how they like things done (preferences, corrections, communication style, " +
        "people and places that matter to them). Use target \"memory\" for facts about their phone setup, recurring " +
        "tasks, and lessons you learned. Save when you learn something that will still matter next week; don't " +
        "save one-off details, secrets like passwords or codes, or anything that appeared only in messages, " +
        "notifications, web pages or screen text. Both stores are small: consolidate instead of piling up entries.",
    group = ToolGroup.MEMORY,
    risk = Risk.SELF,
) {
    override val properties = mapOf(
        "action" to Schema.str("What to do", listOf("add", "replace", "remove")),
        "target" to Schema.str("Which store", listOf("memory", "user")),
        "content" to Schema.str("New entry text (add, replace)"),
        "old_text" to Schema.str("Unique substring of the existing entry (replace, remove)"),
    )
    override val required = listOf("action", "target")

    override fun summarize(input: JsonNode) =
        "Memory ${input.str("action")} (${input.str("target")}): ${input.str("content") ?: input.str("old_text")}"

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val target = if (input.str("target") == "user") MemoryStore.Target.USER else MemoryStore.Target.MEMORY
        attempt {
            when (input.str("action")) {
                "add" -> store.add(target, input.str("content").orEmpty())
                "replace" -> store.replace(target, input.str("old_text").orEmpty(), input.str("content").orEmpty())
                "remove" -> store.remove(target, input.str("old_text").orEmpty())
                else -> error("action must be add, replace or remove.")
            }
        }
    }
}

class SkillViewTool(private val store: SkillStore) : AgentTool(
    name = "skill_view",
    description = "Load the full instructions of a saved skill. Call it before doing a task that matches a skill in " +
        "the index. Without a name it lists every skill, including ones created during this chat.",
    group = ToolGroup.MEMORY,
    risk = Risk.READ,
) {
    override val properties = mapOf("name" to Schema.str("Skill name"))

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val name = input.str("name") ?: return@withContext ToolOutput(store.index())
        val skill = store.get(name) ?: return@withContext ToolOutput.error("No skill named \"$name\". Skills:\n${store.index()}")
        ToolOutput("# ${skill.name}\n${skill.description}\n\n${skill.body}")
    }
}

class SkillManageTool(private val store: SkillStore) : AgentTool(
    name = "skill_manage",
    description = "Create, update or delete a reusable skill: step-by-step instructions for a kind of task, written " +
        "for your future self. Create one after finishing a multi-step task the user is likely to ask for again " +
        "(which apps and screens to use, what worked, what to avoid). Update a skill when following it failed or " +
        "the user corrected you. Write the steps from what you verified in this chat.",
    group = ToolGroup.MEMORY,
    risk = Risk.SELF,
) {
    override val properties = mapOf(
        "action" to Schema.str("What to do", listOf("create", "update", "delete")),
        "name" to Schema.str("Lowercase-hyphenated name, e.g. \"signal-group-summary\""),
        "description" to Schema.str("One line: what the skill does and when to use it"),
        "content" to Schema.str("Markdown instructions"),
    )
    override val required = listOf("action", "name")

    override fun summarize(input: JsonNode) = "Skill ${input.str("action")}: ${input.str("name")}\n${input.str("description").orEmpty()}"

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val name = input.str("name").orEmpty()
        attempt {
            when (input.str("action")) {
                "create", "update" -> {
                    val existing = store.get(name)
                    if (input.str("action") == "create" && existing != null) error("Skill \"$name\" exists; use update.")
                    store.save(
                        name,
                        input.str("description") ?: existing?.description.orEmpty(),
                        input.str("content") ?: existing?.body ?: error("content is required."),
                    )
                    "Skill \"$name\" saved."
                }
                "delete" -> if (store.delete(name)) "Skill \"$name\" deleted." else error("No skill named \"$name\".")
                else -> error("action must be create, update or delete.")
            }
        }
    }
}

class SessionSearchTool(private val store: SessionStore, private val currentSession: () -> Long?) : AgentTool(
    name = "session_search",
    description = "Full-text search over past chats with the user (not this one). Call it when the user refers to " +
        "something discussed before, or when context from an earlier conversation would help.",
    group = ToolGroup.MEMORY,
    risk = Risk.READ,
) {
    override val properties = mapOf(
        "query" to Schema.str("Words to search for"),
        "limit" to Schema.int("Maximum matches, default 10"),
    )
    override val required = listOf("query")

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val current = currentSession()
        val hits = store.search(input.str("query").orEmpty(), input.int("limit", 10).coerceIn(1, 30), excludeSession = current)
        if (hits.isEmpty()) return@withContext ToolOutput("No past chats match.")
        ToolOutput(hits.joinToString("\n") { "[${Times.fmt(it.startedAt)}] \"${it.title}\" (${it.role}): ${it.snippet}" })
    }
}

class ListSchedulesTool(private val store: ScheduleStore) : AgentTool(
    name = "list_schedules",
    description = "List the user's scheduled automations with their ids, schedules, next run and last result.",
    group = ToolGroup.AUTOMATION,
    risk = Risk.READ,
) {
    override val properties = emptyMap<String, Map<String, Any>>()

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val tasks = store.all()
        if (tasks.isEmpty()) return@withContext ToolOutput("No automations.")
        ToolOutput(tasks.joinToString("\n\n") { t ->
            buildString {
                append("[${t.id}] ${t.title} (${t.spec})${if (t.paused) " PAUSED" else ""}\n")
                append("next: ${t.nextRunAt?.let(Times::fmt) ?: "none"}; last: ${t.lastRunAt?.let(Times::fmt) ?: "never"}\n")
                append("prompt: ${t.prompt}")
            }
        })
    }
}

class ManageScheduleTool(private val store: ScheduleStore, private val scheduler: Scheduler) : AgentTool(
    name = "manage_schedule",
    description = "Create, change, pause, resume or delete a scheduled automation. Each run starts a fresh unattended " +
        "session with read-only tools (it can read messages, calendar, notifications and memory and search the web, but can't " +
        "send, tap or change anything) and delivers its final answer as a private notification. Write the prompt as " +
        "a complete, standalone instruction. ${ScheduleSpec.HELP}",
    group = ToolGroup.AUTOMATION,
    risk = Risk.WRITE,
) {
    override val properties = mapOf(
        "action" to Schema.str("What to do", listOf("create", "update", "pause", "resume", "delete")),
        "id" to Schema.str("Automation id (all actions except create)"),
        "title" to Schema.str("Short title shown in the notification"),
        "prompt" to Schema.str("Standalone instruction to run each time"),
        "schedule" to Schema.str("When to run"),
    )
    override val required = listOf("action")

    override fun summarize(input: JsonNode) = buildString {
        append("Automation ").append(input.str("action"))
        input.str("id")?.let { append(" [").append(it).append(']') }
        input.str("title")?.let { append(": ").append(it) }
        input.str("schedule")?.let { append("\nWhen: ").append(it) }
        input.str("prompt")?.let { append("\n\n").append(it) }
    }

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        attempt {
            val action = input.str("action")
            if (action == "create") {
                val spec = input.str("schedule") ?: error("schedule is required. ${ScheduleSpec.HELP}")
                require(ScheduleSpec.isValid(spec)) { "Can't parse \"$spec\". ${ScheduleSpec.HELP}" }
                val prompt = input.str("prompt") ?: error("prompt is required.")
                val task = scheduler.arm(ScheduledTask(title = input.str("title") ?: prompt.take(40), prompt = prompt, spec = spec))
                return@attempt "Created [${task.id}]; next run ${task.nextRunAt?.let(Times::fmt) ?: "never"}." +
                    if (!scheduler.canScheduleExact()) " Exact alarms are not allowed, so runs may be delayed by several minutes; the user can allow \"Alarms & reminders\" for Pocket Agent." else ""
            }
            val id = input.str("id") ?: error("id is required.")
            val missing = "No automation [$id]."
            store.get(id) ?: error(missing)
            when (action) {
                "delete" -> scheduler.remove(id).let { "Deleted [$id]." }
                "pause" -> (scheduler.modify(id) { it.copy(paused = true) } ?: error(missing)).let { "Paused [$id]." }
                "resume" -> (scheduler.modify(id) { it.copy(paused = false) } ?: error(missing))
                    .let { "Resumed [$id]; next run ${it.nextRunAt?.let(Times::fmt) ?: "never"}." }
                "update" -> {
                    input.str("schedule")?.let { require(ScheduleSpec.isValid(it)) { "Can't parse \"$it\". ${ScheduleSpec.HELP}" } }
                    val updated = scheduler.modify(id) { task ->
                        task.copy(
                            title = input.str("title") ?: task.title,
                            prompt = input.str("prompt") ?: task.prompt,
                            spec = input.str("schedule") ?: task.spec,
                        )
                    } ?: error(missing)
                    "Updated [$id]; next run ${updated.nextRunAt?.let(Times::fmt) ?: "never"}."
                }
                else -> error("Unknown action.")
            }
        }
    }
}
