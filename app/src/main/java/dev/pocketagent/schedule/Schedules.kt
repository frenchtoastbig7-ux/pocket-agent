package dev.pocketagent.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.device.AgentRunService
import dev.pocketagent.util.Times
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

data class ScheduledTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val prompt: String,
    val spec: String,
    val paused: Boolean = false,
    val nextRunAt: Long? = null,
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("prompt", prompt).put("spec", spec).put("paused", paused)
        .put("nextRunAt", nextRunAt ?: JSONObject.NULL).put("lastRunAt", lastRunAt ?: JSONObject.NULL)
        .put("lastResult", lastResult ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = ScheduledTask(
            id = o.getString("id"),
            title = o.optString("title"),
            prompt = o.getString("prompt"),
            spec = o.getString("spec"),
            paused = o.optBoolean("paused"),
            nextRunAt = if (o.isNull("nextRunAt")) null else o.getLong("nextRunAt"),
            lastRunAt = if (o.isNull("lastRunAt")) null else o.getLong("lastRunAt"),
            lastResult = if (o.isNull("lastResult")) null else o.getString("lastResult"),
        )
    }
}

/** A small, predictable schedule language instead of raw cron, so the model can't produce surprising expressions. */
object ScheduleSpec {
    const val HELP = "Schedule formats: \"daily 07:30\", \"weekdays 08:00\", \"weekly mon 09:00\", " +
        "\"every 2h\" or \"every 30m\" (at least 15 minutes), \"once 2026-09-14T09:00\". Times are the phone's local time."

    private val EVERY = Regex("^every (\\d+) ?(m|min|mins|minutes?|h|hr|hrs|hours?)$")
    private val DAILY = Regex("^daily (\\d{1,2}:\\d{2})$")
    private val WEEKDAYS = Regex("^weekdays (\\d{1,2}:\\d{2})$")
    private val WEEKLY = Regex("^weekly (mon|tue|wed|thu|fri|sat|sun)[a-z]* (\\d{1,2}:\\d{2})$")
    private val ONCE = Regex("^once (.+)$")

    fun isValid(spec: String): Boolean {
        val s = spec.trim().lowercase()
        EVERY.matchEntire(s)?.let { return minutes(it) >= 15 }
        return listOf(DAILY, WEEKDAYS, WEEKLY).any { r -> r.matchEntire(s)?.let { time(it.groupValues.last()) != null } == true } ||
            ONCE.matchEntire(s)?.let { Times.parse(it.groupValues[1]) != null } == true
    }

    /** The first run strictly after [from], or null when the schedule is finished or invalid. */
    fun nextAfter(spec: String, from: ZonedDateTime): ZonedDateTime? {
        val s = spec.trim().lowercase()
        EVERY.matchEntire(s)?.let { m -> return minutes(m).takeIf { it >= 15 }?.let { from.plusMinutes(it) } }
        DAILY.matchEntire(s)?.let { m -> return nextMatching(from, time(m.groupValues[1]) ?: return null) { true } }
        WEEKDAYS.matchEntire(s)?.let { m ->
            return nextMatching(from, time(m.groupValues[1]) ?: return null) { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }
        }
        WEEKLY.matchEntire(s)?.let { m ->
            val day = DayOfWeek.entries.first { it.name.lowercase().startsWith(m.groupValues[1]) }
            return nextMatching(from, time(m.groupValues[2]) ?: return null) { it == day }
        }
        ONCE.matchEntire(s)?.let { m -> return Times.parse(m.groupValues[1], from.zone)?.takeIf { it.isAfter(from) } }
        return null
    }

    private fun minutes(m: MatchResult): Long {
        val n = m.groupValues[1].toLong()
        return if (m.groupValues[2].startsWith("h")) n * 60 else n
    }

    private fun time(text: String): LocalTime? = runCatching {
        val (h, min) = text.split(":").map { it.toInt() }
        LocalTime.of(h, min)
    }.getOrNull()

    private fun nextMatching(from: ZonedDateTime, time: LocalTime, dayOk: (DayOfWeek) -> Boolean): ZonedDateTime? {
        var candidate = from.toLocalDate().atTime(time).atZone(from.zone)
        repeat(8) {
            if (candidate.isAfter(from) && dayOk(candidate.dayOfWeek)) return candidate
            candidate = candidate.plusDays(1)
        }
        return null
    }
}

class ScheduleStore(context: Context) {
    private val file = File(context.filesDir, "schedules.json")

    @Synchronized
    fun all(): List<ScheduledTask> {
        if (!file.exists()) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrElse { return emptyList() }
        return (0 until array.length()).map { ScheduledTask.fromJson(array.getJSONObject(it)) }
    }

    @Synchronized
    fun get(id: String): ScheduledTask? = all().firstOrNull { it.id == id }

    @Synchronized
    fun put(task: ScheduledTask) = write(all().filterNot { it.id == task.id } + task)

    @Synchronized
    fun remove(id: String) = write(all().filterNot { it.id == id })

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun write(tasks: List<ScheduledTask>) {
        file.writeText(JSONArray().apply { tasks.forEach { put(it.toJson()) } }.toString())
    }
}

/** Arms one AlarmManager alarm per task. No push services, no WorkManager; alarms survive reboots via [ScheduleReceiver]. */
class Scheduler(private val context: Context, private val store: ScheduleStore) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = alarms.canScheduleExactAlarms()

    /** Arms the next run. With [keepFutureRun], a still-upcoming stored run time is kept instead of recomputed. */
    fun arm(task: ScheduledTask, keepFutureRun: Boolean = false): ScheduledTask {
        alarms.cancel(pendingIntent(task.id))
        val now = System.currentTimeMillis()
        val next = when {
            task.paused -> null
            keepFutureRun && task.nextRunAt != null && task.nextRunAt > now -> task.nextRunAt
            else -> ScheduleSpec.nextAfter(task.spec, ZonedDateTime.now())?.toInstant()?.toEpochMilli()
        }
        val updated = task.copy(nextRunAt = next)
        store.put(updated)
        val at = updated.nextRunAt ?: return updated
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(task.id))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(task.id))
        }
        return updated
    }

    fun remove(id: String) {
        alarms.cancel(pendingIntent(id))
        store.remove(id)
    }

    fun rearmAll() = store.all().forEach { arm(it, keepFutureRun = true) }

    fun cancelAll() = store.all().forEach { alarms.cancel(pendingIntent(it.id)) }

    private fun pendingIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_RUN).putExtra(ScheduleReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PocketAgentApp
        when (intent.action) {
            ACTION_RUN -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                context.startForegroundService(AgentRunService.scheduledRunIntent(context, id))
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> app.scheduler.rearmAll()
        }
    }

    companion object {
        const val ACTION_RUN = "dev.pocketagent.RUN_SCHEDULED"
        const val EXTRA_ID = "task_id"
    }
}
