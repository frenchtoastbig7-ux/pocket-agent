package dev.pocketagent

import android.content.Context
import dev.pocketagent.agent.ApprovalDecision
import dev.pocketagent.device.AgentAccessibilityService
import dev.pocketagent.device.Notifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PendingApproval(
    val title: String,
    val summary: String,
    /** Label for the "allow more than once" button, or null when only a one-off approval is offered. */
    val forTaskLabel: String?,
    val decision: CompletableDeferred<ApprovalDecision>,
)

/**
 * The one place where tool calls wait for a person, whether they come from the in-app chat or from Claude Code over
 * the bridge. Requests are handled one at a time. The prompt goes to the in-app dialog while Pocket Agent is on
 * screen, otherwise to the accessibility overlay, otherwise to a notification that opens the app.
 */
class ApprovalBroker(private val context: Context) {
    val pending = MutableStateFlow<PendingApproval?>(null)

    /** True while MainActivity is resumed. */
    @Volatile
    var uiVisible = false
        private set

    /**
     * True from the moment a prompt is requested until it is answered and taken down. Screen actions from every entry
     * point (chat and bridge) refuse to run while this is set, so no tap or gesture can land on an Allow button.
     */
    @Volatile
    var prompting = false
        private set

    private val oneAtATime = Mutex()

    /** Called on the main thread as MainActivity resumes and pauses. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        // A prompt that was showing in the app when the user left would otherwise wait with no sign outside the app.
        val waiting = pending.value ?: return
        if (visible) Notifications.clearApproval(context) else Notifications.approvalNeeded(context, waiting.title)
    }

    suspend fun request(title: String, summary: String, forTaskLabel: String?, timeoutMs: Long? = null): ApprovalDecision =
        oneAtATime.withLock {
            val decision = CompletableDeferred<ApprovalDecision>()
            // The try covers the setup too: a cancellation while returning from Main must still take the prompt down.
            try {
                prompting = true
                withContext(Dispatchers.Main) {
                    val overlay = AgentAccessibilityService.instance
                    if (!uiVisible && overlay != null) {
                        overlay.showApproval(title, summary, forTaskLabel) { decision.complete(it) }
                    } else {
                        pending.value = PendingApproval(title, summary, forTaskLabel, decision)
                        if (!uiVisible) Notifications.approvalNeeded(context, title)
                    }
                }
                if (timeoutMs == null) decision.await()
                else withTimeoutOrNull(timeoutMs) { decision.await() } ?: ApprovalDecision.DENY
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    pending.value = null
                    AgentAccessibilityService.instance?.dismissApproval()
                    Notifications.clearApproval(context)
                    prompting = false
                }
            }
        }

    /** Answers [approval], the prompt the dialog is showing. A tap that arrives after it was replaced is ignored. */
    fun resolve(approval: PendingApproval, decision: ApprovalDecision) {
        if (pending.value === approval) approval.decision.complete(decision)
    }
}
