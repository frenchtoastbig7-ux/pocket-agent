package dev.pocketagent.memory

import android.content.Context
import java.io.File

/**
 * Hermes-style bounded memory: MEMORY.md (the agent's notes) and USER.md (the user profile), entries separated by
 * "§". Files use the same layout as Hermes so they can be copied between the two. Stored in app-private,
 * credential-encrypted storage.
 */
class MemoryStore(context: Context) {

    enum class Target(val fileName: String, val limit: Int, val heading: String) {
        MEMORY("MEMORY.md", 2200, "MEMORY (your notes: facts about the user's setup, conventions, lessons learned)"),
        USER("USER.md", 1375, "USER PROFILE (who the user is, their preferences and communication style)"),
    }

    private val dir = File(context.filesDir, "memories").apply { mkdirs() }

    @Synchronized
    fun entries(target: Target): List<String> {
        val file = File(dir, target.fileName)
        if (!file.exists()) return emptyList()
        return file.readText().split(DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Synchronized
    fun setEntries(target: Target, entries: List<String>) {
        val cleaned = entries.map { it.trim() }.filter { it.isNotEmpty() }
        File(dir, target.fileName).writeText(cleaned.joinToString(DELIMITER))
    }

    @Synchronized
    fun add(target: Target, content: String): String {
        val text = content.trim()
        require(text.isNotEmpty()) { "Nothing to add." }
        val current = entries(target)
        if (current.any { it.equals(text, ignoreCase = true) }) return "Already saved."
        val next = current + text
        checkLimit(target, next)
        setEntries(target, next)
        return "Saved. ${usage(target, next)}"
    }

    @Synchronized
    fun replace(target: Target, oldText: String, content: String): String {
        val current = entries(target)
        val index = uniqueMatch(current, oldText)
        val next = current.toMutableList().apply { this[index] = content.trim() }
        checkLimit(target, next)
        setEntries(target, next)
        return "Replaced. ${usage(target, next)}"
    }

    @Synchronized
    fun remove(target: Target, oldText: String): String {
        val current = entries(target)
        val index = uniqueMatch(current, oldText)
        val next = current.toMutableList().apply { removeAt(index) }
        setEntries(target, next)
        return "Removed. ${usage(target, next)}"
    }

    /** Rendered once per session into the system prompt; mid-session edits apply from the next session. */
    fun snapshot(): String = Target.entries.joinToString("\n\n") { target ->
        val list = entries(target)
        buildString {
            append("== ").append(target.heading).append(" [").append(usage(target, list)).append("] ==\n")
            append(if (list.isEmpty()) "(empty)" else list.joinToString("\n§\n"))
        }
    }

    fun clear() = Target.entries.forEach { File(dir, it.fileName).delete() }

    private fun uniqueMatch(entries: List<String>, oldText: String): Int {
        val needle = oldText.trim()
        require(needle.isNotEmpty()) { "old_text is required: a unique substring of the entry to change." }
        val matches = entries.indices.filter { entries[it].contains(needle, ignoreCase = true) }
        require(matches.isNotEmpty()) { "No entry contains \"$needle\"." }
        require(matches.size == 1) { "\"$needle\" matches ${matches.size} entries; use a longer substring." }
        return matches.single()
    }

    private fun checkLimit(target: Target, entries: List<String>) {
        val size = entries.joinToString(DELIMITER).length
        require(size <= target.limit) {
            "${target.fileName} would be $size/${target.limit} characters. Consolidate or remove entries first, then retry."
        }
    }

    private fun usage(target: Target, entries: List<String>): String {
        val size = entries.joinToString(DELIMITER).length
        return "${size * 100 / target.limit}% — $size/${target.limit} chars"
    }

    private companion object {
        const val DELIMITER = "\n§\n"
    }
}
