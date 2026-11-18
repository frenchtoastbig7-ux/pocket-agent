package dev.pocketagent.tools

import android.content.Context
import android.graphics.BitmapFactory
import com.fasterxml.jackson.databind.JsonNode
import dev.pocketagent.agent.AgentTool
import dev.pocketagent.agent.Risk
import dev.pocketagent.agent.Schema
import dev.pocketagent.agent.ToolGroup
import dev.pocketagent.agent.ToolOutput
import dev.pocketagent.agent.bool
import dev.pocketagent.agent.str
import dev.pocketagent.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * The only part of storage file tools can touch: Android/data/dev.pocketagent/files/workspace.
 * Every path is canonicalized and must stay inside it, so "../" and symlinks can't escape.
 */
class Workspace(context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "workspace").apply { mkdirs() }.canonicalFile

    fun resolve(path: String?): File? {
        val file = File(root, path.orEmpty().trim().trimStart('/')).canonicalFile
        return file.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    fun relative(file: File): String = file.relativeTo(root).path.ifEmpty { "." }
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
private const val MAX_TEXT_CHARS = 200_000

class ListFilesTool(private val ws: Workspace) : AgentTool(
    name = "list_files",
    description = "List files and folders in the agent workspace folder on the phone.",
    group = ToolGroup.FILES,
    risk = Risk.READ,
) {
    override val properties = mapOf("path" to Schema.str("Folder inside the workspace, default the top level"))

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val dir = ws.resolve(input.str("path")) ?: return@withContext ToolOutput.error("Path is outside the workspace.")
        if (!dir.isDirectory) return@withContext ToolOutput.error("${ws.relative(dir)} is not a folder.")
        val entries = dir.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        if (entries.isEmpty()) return@withContext ToolOutput("${ws.relative(dir)} is empty.")
        ToolOutput(entries.joinToString("\n") { if (it.isDirectory) "${ws.relative(it)}/" else "${ws.relative(it)}  ${it.length()} bytes" })
    }
}

class ReadFileTool(private val ws: Workspace) : AgentTool(
    name = "read_file",
    description = "Read a file from the agent workspace. Text files are returned as text; JPEG/PNG/WebP images are " +
        "returned as images you can see.",
    group = ToolGroup.FILES,
    risk = Risk.READ,
) {
    override val properties = mapOf("path" to Schema.str("File path inside the workspace"))
    override val required = listOf("path")

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val file = ws.resolve(input.str("path")) ?: return@withContext ToolOutput.error("Path is outside the workspace.")
        if (!file.isFile) return@withContext ToolOutput.error("${ws.relative(file)} does not exist.")
        if (file.extension.lowercase() in IMAGE_EXTENSIONS) {
            val bmp = BitmapFactory.decodeFile(file.path) ?: return@withContext ToolOutput.error("Could not decode image.")
            val scale = Images.MAX_EDGE.toFloat() / max(bmp.width, bmp.height)
            return@withContext ToolOutput("Image ${ws.relative(file)} (${bmp.width}x${bmp.height})", Images.jpeg(bmp, scale))
        }
        val text = file.readText()
        ToolOutput(
            if (text.length > MAX_TEXT_CHARS) text.take(MAX_TEXT_CHARS) + "\n[truncated: file has ${text.length} characters]"
            else text
        )
    }
}

class WriteFileTool(private val ws: Workspace) : AgentTool(
    name = "write_file",
    description = "Create or overwrite a text file in the agent workspace (or append to it). Use it to save notes, " +
        "drafts, or results the user asked you to keep.",
    group = ToolGroup.FILES,
    risk = Risk.WRITE,
) {
    override val properties = mapOf(
        "path" to Schema.str("File path inside the workspace"),
        "content" to Schema.str("Text to write"),
        "append" to Schema.bool("Append instead of overwriting"),
    )
    override val required = listOf("path", "content")

    override fun summarize(input: JsonNode): String {
        val content = input.str("content").orEmpty()
        val verb = if (input.bool("append")) "Append to" else "Write"
        return "$verb workspace/${input.str("path")} (${content.length} chars)\n\n" +
            content.take(600) + if (content.length > 600) "…" else ""
    }

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val file = ws.resolve(input.str("path")) ?: return@withContext ToolOutput.error("Path is outside the workspace.")
        if (file == ws.root || file.isDirectory) return@withContext ToolOutput.error("Path is a folder.")
        file.parentFile?.mkdirs()
        val content = input.str("content").orEmpty()
        if (input.bool("append")) file.appendText(content) else file.writeText(content)
        ToolOutput("Wrote ${content.length} characters to ${ws.relative(file)}.")
    }
}
