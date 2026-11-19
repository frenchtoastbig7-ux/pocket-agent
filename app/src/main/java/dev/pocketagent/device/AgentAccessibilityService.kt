package dev.pocketagent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.agent.ApprovalDecision
import dev.pocketagent.agent.ToolOutput
import dev.pocketagent.util.Images
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Screen reading and input for the agent, plus the approval overlay shown on top of other apps.
 *
 * Coordinates exposed to the model are in "agent space": the screen scaled so its longest edge is
 * [Images.MAX_EDGE]. read_screen, screenshot, tap and swipe all use that same space, so the model
 * never has to convert between a screenshot and the real display resolution.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    /**
     * The id of nodes[0]. Ids keep counting up across snapshots instead of restarting at 0, so an id from an older
     * read_screen (or from another entry point's read_screen) can never point at a different element in a newer one.
     */
    private var firstId = 0
    private var overlay: View? = null

    /** Answers the approval the overlay is showing. Main thread only. */
    private var overlayDecision: ((ApprovalDecision) -> Unit)? = null

    private val blocked = ToolOutput.error("An approval prompt is on screen, so screen actions are paused. Try again once it has been answered.")

    /** True while any approval prompt is up; nothing may tap, type or gesture then (it could land on Allow). */
    private fun prompting(): Boolean = (application as PocketAgentApp).approvals.prompting

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        // A prompt nobody can see any more must not leave its request waiting forever.
        overlayDecision?.invoke(ApprovalDecision.DENY)
        removeOverlay()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    private fun screenBounds(): Rect = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds

    /** Screen pixels → agent space factor (≤ 1). */
    private fun scale(): Float {
        val b = screenBounds()
        return min(1f, Images.MAX_EDGE.toFloat() / max(b.width(), b.height()))
    }

    // ---- Reading -------------------------------------------------------------------------------

    fun describeScreen(maxElements: Int): String {
        val root = rootInActiveWindow ?: return "No readable window is active (the screen may be locked)."
        val s = scale()
        val b = screenBounds()
        val sb = StringBuilder()
        sb.append("App: ").append(root.packageName).append("   screen ")
            .append((b.width() * s).roundToInt()).append('x').append((b.height() * s).roundToInt()).append('\n')
        synchronized(nodes) {
            firstId = if (firstId > 1_000_000) 0 else firstId + nodes.size
            nodes.clear()
            walk(root, 0, sb, maxElements, s)
            if (nodes.size >= maxElements) sb.append("[element limit reached; scroll or raise max_elements]\n")
        }
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder, max: Int, s: Float) {
        if (!n.isVisibleToUser || nodes.size >= max) return
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val hint = n.hintText?.toString()?.trim().orEmpty()
        val actionable = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable
        var childDepth = depth
        if (actionable || text.isNotEmpty() || desc.isNotEmpty()) {
            val id = firstId + nodes.size
            nodes.add(n)
            val r = Rect().also(n::getBoundsInScreen)
            sb.append("  ".repeat(min(depth, 12))).append('[').append(id).append("] ")
                .append(n.className?.toString()?.substringAfterLast('.') ?: "View")
            if (text.isNotEmpty()) sb.append(" \"").append(text.take(300)).append('"')
            if (desc.isNotEmpty() && desc != text) sb.append(" desc=\"").append(desc.take(150)).append('"')
            if (hint.isNotEmpty() && text.isEmpty()) sb.append(" hint=\"").append(hint.take(80)).append('"')
            val flags = listOfNotNull(
                "click".takeIf { n.isClickable },
                "edit".takeIf { n.isEditable },
                "scroll".takeIf { n.isScrollable },
                "checked".takeIf { n.isCheckable && n.isChecked },
                "selected".takeIf { n.isSelected },
                "disabled".takeIf { !n.isEnabled },
                "password".takeIf { n.isPassword },
            )
            if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
            sb.append(" @").append((r.exactCenterX() * s).roundToInt()).append(',').append((r.exactCenterY() * s).roundToInt()).append('\n')
            childDepth = depth + 1
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, childDepth, sb, max, s) }
    }

    fun elementLabel(id: Int): String? {
        val n = synchronized(nodes) { nodes.getOrNull(id - firstId) } ?: return null
        val label = (n.text ?: n.contentDescription ?: n.hintText)?.toString()?.take(80)
        val kind = n.className?.toString()?.substringAfterLast('.') ?: "View"
        return "[$id] $kind${label?.let { " \"$it\"" }.orEmpty()} in ${n.packageName}"
    }

    suspend fun screenshotJpeg(): Pair<ByteArray, String>? = suspendCancellableCoroutine { cont ->
        val s = scale()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (bitmap == null) {
                    if (cont.isActive) cont.resume(null)
                    return
                }
                val jpeg = Images.jpeg(bitmap, s)
                val size = "${(bitmap.width * s).roundToInt()}x${(bitmap.height * s).roundToInt()}"
                if (cont.isActive) cont.resume(jpeg to size)
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    // ---- Acting --------------------------------------------------------------------------------

    /** The element for [id], or null when the id is from an older snapshot or the view no longer exists. */
    private fun node(id: Int): AccessibilityNodeInfo? =
        synchronized(nodes) { nodes.getOrNull(id - firstId) }?.takeIf { it.refresh() }

    private fun gone(id: Int) = ToolOutput.error("Element [$id] is gone or from an older read_screen. Call read_screen again.")

    suspend fun tapElement(id: Int, longPress: Boolean): ToolOutput {
        if (prompting()) return blocked
        val target = node(id) ?: return gone(id)
        var n: AccessibilityNodeInfo? = target
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (n != null) {
            val can = if (longPress) n.isLongClickable else n.isClickable
            if (can && n.performAction(action)) return ToolOutput("Tapped ${elementLabel(id)}.")
            n = n.parent
        }
        val r = Rect().also(target::getBoundsInScreen)
        return if (gestureTap(r.exactCenterX(), r.exactCenterY(), longPress)) ToolOutput("Tapped the center of [$id].")
        else ToolOutput.error("Tap on [$id] failed.")
    }

    suspend fun tapPoint(x: Int, y: Int, longPress: Boolean): ToolOutput {
        val s = scale()
        return if (gestureTap(x / s, y / s, longPress)) ToolOutput("Tapped at $x,$y.") else ToolOutput.error("Tap at $x,$y failed.")
    }

    fun setText(id: Int, text: String, submit: Boolean): ToolOutput {
        if (prompting()) return blocked
        val n = node(id) ?: return gone(id)
        if (!n.isEditable) return ToolOutput.error("Element [$id] is not an editable field.")
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return ToolOutput.error("The field [$id] refused the text.")
        if (submit && !n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            return ToolOutput.error("Typed into [$id], but pressing enter failed; tap the send/submit button instead.")
        }
        return ToolOutput("Typed into [$id]${if (submit) " and pressed enter" else ""}.")
    }

    /**
     * The accessibility action that moves [n]'s content in [direction], or null when it offers none we can trust.
     * Directional actions are exact. Forward/backward follow the container's own axis, so they are used only for a
     * direction along that axis; otherwise a vertical list asked to go "left" would scroll up and report success.
     */
    private fun scrollAction(n: AccessibilityNodeInfo, direction: String): Int? {
        val offered = n.actionList.map { it.id }.toSet()
        val exact = when (direction) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
        }.id
        if (exact in offered) return exact
        val info = n.collectionInfo
        val className = n.className?.toString().orEmpty()
        val horizontal = (info != null && info.rowCount <= 1 && info.columnCount > 1) ||
            "Horizontal" in className || "ViewPager" in className
        val sideways = direction == "left" || direction == "right"
        if (sideways != horizontal) return null
        return if (direction == "up" || direction == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    }

    suspend fun scroll(id: Int?, direction: String): ToolOutput {
        if (prompting()) return blocked
        if (id != null) {
            var n = node(id) ?: return gone(id)
            while (true) {
                val action = if (n.isScrollable) scrollAction(n, direction) else null
                if (action != null && n.performAction(action)) return ToolOutput("Scrolled $direction in [$id].")
                n = n.parent ?: break
            }
        }
        val b = screenBounds()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val dx = b.width() * 0.3f
        val dy = b.height() * 0.3f
        // Content moves opposite to the finger: to scroll down, swipe up.
        val (x1, y1, x2, y2) = when (direction) {
            "up" -> listOf(cx, cy - dy, cx, cy + dy)
            "left" -> listOf(cx - dx, cy, cx + dx, cy)
            "right" -> listOf(cx + dx, cy, cx - dx, cy)
            else -> listOf(cx, cy + dy, cx, cy - dy)
        }
        return if (gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 350)) ToolOutput("Swiped to scroll $direction.")
        else ToolOutput.error("Scroll failed.")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ToolOutput {
        val s = scale()
        val path = Path().apply { moveTo(x1 / s, y1 / s); lineTo(x2 / s, y2 / s) }
        return if (gesture(path, durationMs)) ToolOutput("Swiped.") else ToolOutput.error("Swipe failed.")
    }

    fun global(action: String): ToolOutput {
        if (prompting()) return blocked
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return ToolOutput.error("Unknown action $action.")
        }
        return if (performGlobalAction(code)) ToolOutput("Performed $action.") else ToolOutput.error("$action failed.")
    }

    private suspend fun gestureTap(x: Float, y: Float, longPress: Boolean): Boolean =
        gesture(Path().apply { moveTo(x, y) }, if (longPress) 700 else 60)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        // Every tap, swipe and scroll gesture from every entry point comes through here.
        if (prompting()) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val accepted = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, main)
        if (!accepted && cont.isActive) cont.resume(false)
    }

    // ---- Approval overlay ----------------------------------------------------------------------

    /**
     * Shows an approval card over whatever app is in front. The card takes touches, and a dispatched gesture is
     * hit-tested like a finger, so every action in this service first checks [prompting] and refuses while a prompt
     * is up: neither the chat nor a bridge call with a standing allowance can press Allow.
     */
    fun showApproval(title: String, detail: String, forTaskLabel: String?, onDecision: (ApprovalDecision) -> Unit) {
        main.post {
            // A prompt replaced before it was answered counts as declined.
            overlayDecision?.invoke(ApprovalDecision.DENY)
            removeOverlay()
            overlayDecision = onDecision
            val dp = resources.displayMetrics.density
            fun px(v: Int) = (v * dp).roundToInt()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(18), px(16), px(18), px(12))
                background = GradientDrawable().apply {
                    setColor(Color.argb(245, 28, 28, 32))
                    cornerRadius = px(22).toFloat()
                }
            }
            card.addView(TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            })
            // The whole detail (the full text about to be typed, say) is reachable by scrolling; the box stops
            // growing at a fixed height so the buttons stay on screen.
            val detailBox = object : ScrollView(this) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(px(260), MeasureSpec.AT_MOST))
            }
            detailBox.addView(TextView(this).apply {
                text = detail
                setTextColor(Color.rgb(220, 220, 225))
                textSize = 14f
                setPadding(0, px(8), 0, px(8))
            })
            card.addView(detailBox)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            fun button(label: String, decision: ApprovalDecision) = Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    removeOverlay()
                    overlayDecision = null
                    onDecision(decision)
                }
            }
            row.addView(button("Deny", ApprovalDecision.DENY))
            if (forTaskLabel != null) row.addView(button(forTaskLabel, ApprovalDecision.FOR_TASK))
            row.addView(button("Allow once", ApprovalDecision.ONCE))
            card.addView(row)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                y = px(32)
                horizontalMargin = 0.03f
            }
            getSystemService(WindowManager::class.java).addView(card, lp)
            overlay = card
        }
    }

    fun dismissApproval() = main.post {
        overlayDecision = null
        removeOverlay()
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }
}
