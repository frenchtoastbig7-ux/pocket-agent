package dev.pocketagent.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.pocketagent.MainActivity
import dev.pocketagent.R

/** All notifications are VISIBILITY_PRIVATE: the lock screen shows only that something happened, never the content. */
object Notifications {
    const val RUNNING_ID = 1
    private const val APPROVAL_ID = 2
    const val BRIDGE_ID = 3
    private const val CH_RUNNING = "running"
    private const val CH_BRIDGE = "bridge"
    private const val CH_APPROVAL = "approval"
    private const val CH_AUTOMATION = "automation"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_RUNNING, "Agent working", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_BRIDGE, "Claude Code bridge", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_APPROVAL, "Approval requests", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_AUTOMATION, "Automation results", NotificationManager.IMPORTANCE_DEFAULT),
            ).onEach { it.lockscreenVisibility = Notification.VISIBILITY_PRIVATE }
        )
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun running(context: Context): Notification {
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, AgentRunService::class.java).setAction(AgentRunService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Pocket Agent is working")
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_agent), "Stop", stop).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    fun bridge(context: Context, state: String): Notification {
        val stop = PendingIntent.getService(
            context, 2,
            dev.pocketagent.bridge.BridgeService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CH_BRIDGE)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("Claude Code bridge")
            .setContentText(state)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_agent), "Stop", stop).build())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    fun updateBridge(context: Context, state: String) =
        context.getSystemService(NotificationManager::class.java).notify(BRIDGE_ID, bridge(context, state))

    fun approvalNeeded(context: Context, title: String) {
        val n = Notification.Builder(context, CH_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText("Open Pocket Agent to review")
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CH_APPROVAL, "Pocket Agent needs approval"))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(APPROVAL_ID, n)
    }

    fun clearApproval(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(APPROVAL_ID)

    fun automationResult(context: Context, taskId: String, title: String, report: String) {
        val n = Notification.Builder(context, CH_AUTOMATION)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(report.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(report))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, CH_AUTOMATION, "Automation finished"))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(1000 + taskId.hashCode() and 0xFFFFFF, n)
    }

    private fun publicVersion(context: Context, channel: String, text: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_agent)
        .setContentTitle(text)
        .build()
}
