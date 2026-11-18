package dev.pocketagent.tools

import com.fasterxml.jackson.databind.JsonNode
import dev.pocketagent.agent.AgentTool
import dev.pocketagent.agent.Risk
import dev.pocketagent.agent.Schema
import dev.pocketagent.agent.ToolGroup
import dev.pocketagent.agent.ToolOutput
import dev.pocketagent.agent.UNTRUSTED_NOTE
import dev.pocketagent.agent.bool
import dev.pocketagent.agent.int
import dev.pocketagent.agent.intOrNull
import dev.pocketagent.agent.str
import dev.pocketagent.device.AgentAccessibilityService
import kotlinx.coroutines.delay

private val SERVICE_OFF = ToolOutput.error(
    "Screen control is off. Tell the user to enable Pocket Agent under Settings > Accessibility. On GrapheneOS they " +
        "may first need App info > menu > Allow restricted settings."
)

/** Time for the UI to react before the model looks again. */
private suspend fun settle() = delay(700)

private fun elementDetail(input: JsonNode): String {
    val service = AgentAccessibilityService.instance
    return input.intOrNull("element")?.let { service?.elementLabel(it) ?: "[$it]" }
        ?: "point ${input.intOrNull("x")},${input.intOrNull("y")}"
}

class ReadScreenTool : AgentTool(
    name = "read_screen",
    description = "Read the UI of the app currently on screen as a list of elements. Each line has an element id in " +
        "brackets, its type, text, flags like {click,edit,scroll}, and its center @x,y. Call this before tapping or " +
        "typing, and again after each action to see what changed. Prefer it to screenshot; use screenshot when the " +
        "layout or images matter or elements have no text.",
    group = ToolGroup.SCREEN,
    risk = Risk.READ,
) {
    override val properties = mapOf("max_elements" to Schema.int("Maximum elements to return, default 300"))

    override suspend fun run(input: JsonNode): ToolOutput {
        val service = AgentAccessibilityService.instance ?: return SERVICE_OFF
        return ToolOutput(UNTRUSTED_NOTE + "\n" + service.describeScreen(input.int("max_elements", 300).coerceIn(20, 1000)))
    }
}

class ScreenshotTool : AgentTool(
    name = "screenshot",
    description = "Capture the screen as an image. Pixel coordinates in the image match the @x,y coordinates used by " +
        "read_screen, tap and swipe. Apps that block screenshots (banking, some messengers) come back black.",
    group = ToolGroup.SCREEN,
    risk = Risk.READ,
) {
    override val properties = emptyMap<String, Map<String, Any>>()

    override suspend fun run(input: JsonNode): ToolOutput {
        val service = AgentAccessibilityService.instance ?: return SERVICE_OFF
        val shot = service.screenshotJpeg()
            ?: return ToolOutput.error("Screenshot failed. Android allows at most one per second; wait and retry.")
        return ToolOutput("Screenshot ${shot.second}. $UNTRUSTED_NOTE", imageJpeg = shot.first)
    }
}

class TapTool : AgentTool(
    name = "tap",
    description = "Tap (or long-press) an element from read_screen by its id, or a point x,y. Prefer element ids.",
    group = ToolGroup.SCREEN,
    risk = Risk.EXTERNAL,
) {
    override val properties = mapOf(
        "element" to Schema.int("Element id from the latest read_screen"),
        "x" to Schema.int("X coordinate, if no element id"),
        "y" to Schema.int("Y coordinate, if no element id"),
        "long_press" to Schema.bool("Long-press instead of tap"),
    )

    override fun summarize(input: JsonNode) = (if (input.bool("long_press")) "Long-press " else "Tap ") + elementDetail(input)

    override suspend fun run(input: JsonNode): ToolOutput {
        val service = AgentAccessibilityService.instance ?: return SERVICE_OFF
        val longPress = input.bool("long_press")
        val element = input.intOrNull("element")
        val x = input.intOrNull("x")
        val y = input.intOrNull("y")
        val result = when {
            element != null -> service.tapElement(element, longPress)
            x != null && y != null -> service.tapPoint(x, y, longPress)
            else -> return ToolOutput.error("Give either 'element' or both 'x' and 'y'.")
        }
        settle()
        return ToolOutput(result)
    }
}

class TypeTextTool : AgentTool(
    name = "type_text",
    description = "Replace the text in an editable field (flag {edit} in read_screen). Optionally press enter afterwards.",
    group = ToolGroup.SCREEN,
    risk = Risk.EXTERNAL,
) {
    override val properties = mapOf(
        "element" to Schema.int("Editable element id from the latest read_screen"),
        "text" to Schema.str("Text to put in the field"),
        "submit" to Schema.bool("Press the keyboard's enter/send key afterwards"),
    )
    override val required = listOf("element", "text")

    override fun summarize(input: JsonNode) =
        "Type into ${elementDetail(input)}${if (input.bool("submit")) " and press enter" else ""}\n\n${input.str("text").orEmpty()}"

    override suspend fun run(input: JsonNode): ToolOutput {
        val service = AgentAccessibilityService.instance ?: return SERVICE_OFF
        val element = input.intOrNull("element") ?: return ToolOutput.error("Missing 'element'.")
        val result = service.setText(element, input.str("text").orEmpty(), input.bool("submit"))
        settle()
        return ToolOutput(result)
    }
}

class ScrollTool : AgentTool(
    name = "scroll",
    description = "Scroll a list or page. Give the id of a {scroll} element (or anything inside it), or omit it to swipe the middle of the screen.",
    group = ToolGroup.SCREEN,
    risk = Risk.EXTERNAL,
) {
    override val properties = mapOf(
        "direction" to Schema.str("Direction to move the content", listOf("down", "up", "left", "right")),
        "element" to Schema.int("Scrollable element id"),
    )
    override val required = listOf("direction")

    override fun summarize(input: JsonNode) = "Scroll ${input.str("direction")}" +
        (input.intOrNull("element")?.let { " in ${elementDetail(input)}" } ?: "")

    override suspend fun run(input: JsonNode): ToolOutput {
        val service = AgentAccessibilityService.instance ?: return SERVICE_OFF
        val result = service.scroll(input.intOrNull("element"), input.str("direction") ?: "down")
        settle()
        return ToolOutput(result)
    }
}

class SwipeTool : AgentTool(
    name = "swipe",
    description = "Drag a finger from one point to another, for gestures scroll can't express (sliders, dismissing, carousels).",
    group = ToolGroup.SCREEN,
    risk = Risk.EXTERNAL,
) {
    override val properties = mapOf(
        "x1" to Schema.int("Start x"), "y1" to Schema.int("Start y"),
        "x2" to Schema.int("End x"), "y2" to Schema.int("End y"),
        "duration_ms" to Schema.int("Gesture duration, default 300"),
    )
    override val required = listOf("x1", "y1", "x2", "y2")

    override fun summarize(input: JsonNode) =
        "Swipe from ${input.intOrNull("x1")},${input.intOrNull("y1")} to ${input.intOrNull("x2")},${input.intOrNull("y2")}"

    override suspend fun run(input: JsonNode): ToolOutput {
        val service = AgentAccessibilityService.instance ?: return SERVICE_OFF
        val result = service.swipe(
            input.int("x1", 0), input.int("y1", 0), input.int("x2", 0), input.int("y2", 0),
            input.int("duration_ms", 300).coerceIn(50, 3000).toLong(),
        )
        settle()
        return ToolOutput(result)
    }
}

class GlobalActionTool : AgentTool(
    name = "system_action",
    description = "Press a system navigation action: back, home, recents, or pull down notifications / quick settings.",
    group = ToolGroup.SCREEN,
    risk = Risk.EXTERNAL,
) {
    override val properties = mapOf(
        "action" to Schema.str("Action", listOf("back", "home", "recents", "notifications", "quick_settings")),
    )
    override val required = listOf("action")

    override fun summarize(input: JsonNode) = "System action: ${input.str("action")}"

    override suspend fun run(input: JsonNode): ToolOutput {
        val service = AgentAccessibilityService.instance ?: return SERVICE_OFF
        val result = service.global(input.str("action").orEmpty())
        settle()
        return ToolOutput(result)
    }
}
