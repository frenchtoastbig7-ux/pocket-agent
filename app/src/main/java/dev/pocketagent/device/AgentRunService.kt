package dev.pocketagent.device

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.agent.RunMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that keeps the process alive while the agent works: during a chat turn (so it keeps going
 * while the user is in another app) and for scheduled automations, which run here with read-only tools.
 */
class AgentRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var interactive = false
    private val scheduledJobs = HashMap<String, Job>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PocketAgentApp
        runCatching {
            startForeground(Notifications.RUNNING_ID, Notifications.running(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        when (intent?.action) {
            ACTION_STOP -> {
                app.controller.stop()
                scheduledJobs.values.forEach { it.cancel() }
            }
            ACTION_INTERACTIVE -> interactive = true
            ACTION_INTERACTIVE_DONE -> interactive = false
            ACTION_SCHEDULED -> intent.getStringExtra(EXTRA_TASK_ID)?.let { runScheduled(app, it) }
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    private fun runScheduled(app: PocketAgentApp, taskId: String) {
        if (scheduledJobs.containsKey(taskId)) {
            // No second run on top of the one still going, but the alarm was one-shot: arm the next occurrence.
            scope.launch(Dispatchers.IO) { runCatching { app.scheduler.advance(taskId) } }
            return
        }
        // Nothing is saved once "Wipe all agent data" has run: every write below goes through ifCurrent.
        val epoch = app.dataEpoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // The next run is armed before this one starts, so the schedule doesn't depend on this run finishing.
            val task = withContext(Dispatchers.IO) { app.scheduler.advance(taskId) } ?: return@launch
            val report = try {
                val client = app.factory.client() ?: error("No API key is configured.")
                val loop = app.factory.newLoop(client, RunMode.UNATTENDED, approver = null) { null }
                val sessionId = withContext(Dispatchers.IO) {
                    app.ifCurrent(epoch) {
                        app.sessions.create("automation", task.title).also { app.sessions.addTurn(it, "user", task.prompt) }
                    } ?: throw CancellationException("Data was wiped.")
                }
                val reply = loop.send("Automation \"${task.title}\" is running now.\n\n${task.prompt}") {}
                withContext(Dispatchers.IO) { app.ifCurrent(epoch) { app.sessions.addTurn(sessionId, "assistant", reply) } }
                reply.ifBlank { "(The automation produced no report.)" }
            } catch (_: CancellationException) {
                "Stopped."
            } catch (e: Exception) {
                "Failed: ${e.message ?: e.javaClass.simpleName}"
            }
            // Stop cancels this job; the result is still recorded and shown.
            withContext(NonCancellable + Dispatchers.IO) {
                // Both under the wipe's lock: a wipe either comes first (nothing is shown) or clears the report after.
                app.ifCurrent(epoch) {
                    runCatching { app.scheduler.recordResult(taskId, System.currentTimeMillis(), report.take(1000)) }
                    runCatching { Notifications.automationResult(this@AgentRunService, task.id, task.title, report) }
                }
            }
        }
        scheduledJobs[taskId] = job
        // Runs however the job ends (finished, failed, cancelled before it started), so a stopped automation can be
        // started again and the service doesn't stay in the foreground.
        job.invokeOnCompletion {
            main.post {
                if (scheduledJobs[taskId] === job) scheduledJobs.remove(taskId)
                stopIfIdle()
            }
        }
        job.start()
    }

    private fun stopIfIdle() {
        if (!interactive && scheduledJobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.pocketagent.STOP"
        private const val ACTION_INTERACTIVE = "dev.pocketagent.INTERACTIVE"
        private const val ACTION_INTERACTIVE_DONE = "dev.pocketagent.INTERACTIVE_DONE"
        private const val ACTION_SCHEDULED = "dev.pocketagent.SCHEDULED"
        private const val EXTRA_TASK_ID = "task_id"

        fun scheduledRunIntent(context: Context, taskId: String): Intent =
            Intent(context, AgentRunService::class.java).setAction(ACTION_SCHEDULED).putExtra(EXTRA_TASK_ID, taskId)

        fun startInteractive(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AgentRunService::class.java).setAction(ACTION_INTERACTIVE)) }
        }

        fun stopInteractive(context: Context) {
            // startService is allowed from the background while this service is already in the foreground.
            runCatching { context.startService(Intent(context, AgentRunService::class.java).setAction(ACTION_INTERACTIVE_DONE)) }
        }
    }
}
