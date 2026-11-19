package dev.pocketagent.memory

import android.content.Context
import dev.pocketagent.util.writeTextAtomic
import java.io.File

/**
 * Procedural memory: one folder per skill holding a SKILL.md with agentskills.io-style frontmatter
 * (name, description). Only the index (name + description) goes into the system prompt; bodies load on demand.
 */
class SkillStore(context: Context) {

    data class Skill(val name: String, val description: String, val body: String, val updatedAt: Long)

    private val dir = File(context.filesDir, "skills").apply { mkdirs() }

    @Synchronized
    fun list(): List<Skill> = dir.listFiles().orEmpty()
        .mapNotNull { folder -> parse(File(folder, FILE)) }
        .sortedBy { it.name }

    @Synchronized
    fun get(name: String): Skill? = parse(File(File(dir, name), FILE))

    @Synchronized
    fun save(name: String, description: String, body: String) {
        require(NAME.matches(name)) { "Skill names are lowercase letters, digits and hyphens, e.g. \"weekly-review\"." }
        val oneLine = description.replace(Regex("\\s+"), " ").trim()
        require(oneLine.isNotEmpty()) { "A description is required: say when this skill should be used." }
        require(body.length <= MAX_BODY) { "Skill body is ${body.length} characters; keep it under $MAX_BODY." }
        val folder = File(dir, name).apply { mkdirs() }
        File(folder, FILE).writeTextAtomic("---\nname: $name\ndescription: $oneLine\n---\n\n${body.trim()}\n")
    }

    @Synchronized
    fun delete(name: String): Boolean = NAME.matches(name) && File(dir, name).deleteRecursively()

    fun index(): String {
        val skills = list()
        if (skills.isEmpty()) return "(no skills yet)"
        return skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    private fun parse(file: File): Skill? {
        if (!file.isFile) return null
        val text = file.readText()
        val match = FRONTMATTER.find(text) ?: return null
        val fields = match.groupValues[1].lines()
            .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .toMap()
        val name = fields["name"] ?: file.parentFile?.name ?: return null
        return Skill(name, fields["description"].orEmpty(), text.substring(match.range.last + 1).trim(), file.lastModified())
    }

    private companion object {
        const val FILE = "SKILL.md"
        const val MAX_BODY = 20_000
        val NAME = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        val FRONTMATTER = Regex("\\A---\\n(.*?)\\n---\\n", RegexOption.DOT_MATCHES_ALL)
    }
}
