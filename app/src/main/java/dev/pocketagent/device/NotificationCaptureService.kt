package dev.pocketagent.device

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Keeps an in-memory ring buffer of recent notifications. Nothing is written to disk. */
class NotificationCaptureService : NotificationListenerService() {

    data class Item(val key: String, val postedAt: Long, val packageName: String, val title: String, val text: String)

    companion object {
        private const val CAPACITY = 300
        private val buffer = ArrayDeque<Item>()

        @Volatile
        var connected = false
            private set

        /** Newest first. */
        fun recent(): List<Item> = synchronized(buffer) { buffer.reversed() }
    }

    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications }.getOrNull()?.sortedBy { it.postTime }?.forEach(::add)
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = add(sbn)

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val item = Item(sbn.key, sbn.postTime, sbn.packageName, title, text)
        synchronized(buffer) {
            // Apps re-post the same notification as it updates; keep only the latest version.
            buffer.removeAll { it.key == item.key }
            buffer.addLast(item)
            while (buffer.size > CAPACITY) buffer.removeFirst()
        }
    }
}
