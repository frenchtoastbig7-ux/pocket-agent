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

    private val oneAtATime = Mutex()

    suspend fun request(title: String, summary: String, forTaskLabel: String?, timeoutMs: Long? = null): ApprovalDecision =
        oneAtATime.withLock {
            val decision = CompletableDeferred<ApprovalDecision>()
            withContext(Dispatchers.Main) {
                val overlay = AgentAccessibilityService.instance
                if (!uiVisible && overlay != null) {
                    overlay.showApproval(title, summary, forTaskLabel) { decision.complete(it) }
                } else {
                    pending.value = PendingApproval(title, summary, forTaskLabel, decision)
                    if (!uiVisible) Notifications.approvalNeeded(context, title)
                }
            }
            try {
                if (timeoutMs == null) decision.await()
                else withTimeoutOrNull(timeoutMs) { decision.await() } ?: ApprovalDecision.DENY
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    pending.value = null
                    AgentAccessibilityService.instance?.dismissApproval()
                    Notifications.clearApproval(context)
                }
            }
        }

    fun resolve(decision: ApprovalDecision) {
        pending.value?.decision?.complete(decision)
    }
}
