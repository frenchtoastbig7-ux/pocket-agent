package dev.pocketagent.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.device.AgentRunService
import dev.pocketagent.util.Times

@Composable
fun AutomationsScreen(app: PocketAgentApp) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val tasks = remember(refresh) { app.schedules.all().sortedBy { it.nextRunAt ?: Long.MAX_VALUE } }
    val exact = remember(refresh) { app.scheduler.canScheduleExact() }
    LaunchedEffect(Unit) { refresh++ }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Automations", style = MaterialTheme.typography.titleLarge)
            Text(
                "Create them by asking in chat, e.g. “Every weekday at 7:30, summarize my calendar and overnight notifications.” " +
                    "Each run uses read-only tools and reports back in a private notification.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!exact) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        )
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Allow exact timing (Alarms & reminders)") }
            }
        }
        if (tasks.isEmpty()) item { Text("No automations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(tasks, key = { it.id }) { task ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    Text("${task.spec}${if (task.paused) " · paused" else ""}", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Next: ${task.nextRunAt?.let(Times::fmt) ?: "not scheduled"} · Last: ${task.lastRunAt?.let(Times::fmt) ?: "never"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(task.prompt, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    task.lastResult?.let {
                        Text("Last result: ${it.take(300)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                    Row {
                        TextButton(onClick = {
                            context.startForegroundService(AgentRunService.scheduledRunIntent(context, task.id))
                        }) { Text("Run now") }
                        TextButton(onClick = {
                            app.scheduler.arm(task.copy(paused = !task.paused))
                            refresh++
                        }) { Text(if (task.paused) "Resume" else "Pause") }
                        TextButton(onClick = {
                            app.scheduler.remove(task.id)
                            refresh++
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
