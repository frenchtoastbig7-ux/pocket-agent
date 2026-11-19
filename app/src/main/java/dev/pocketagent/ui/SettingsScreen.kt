package dev.pocketagent.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.agent.ToolGroup
import dev.pocketagent.device.AgentAccessibilityService
import dev.pocketagent.device.NotificationCaptureService
import dev.pocketagent.util.hasPermission

private val DATA_PERMISSIONS = listOf(
    Manifest.permission.READ_SMS to "Read SMS",
    Manifest.permission.SEND_SMS to "Send SMS",
    Manifest.permission.READ_CONTACTS to "Contacts",
    Manifest.permission.READ_CALENDAR to "Read calendar",
    Manifest.permission.WRITE_CALENDAR to "Add calendar events",
    Manifest.permission.READ_CALL_LOG to "Call log",
    Manifest.permission.POST_NOTIFICATIONS to "Show notifications",
    Manifest.permission.RECORD_AUDIO to "Microphone (voice input)",
)

@Composable
fun SettingsScreen(app: PocketAgentApp, voice: Voice, onSecureWindowChanged: (Boolean) -> Unit) {
    // A wipe resets every preference, so the whole screen is rebuilt from the stored values afterwards.
    var wipes by remember { mutableIntStateOf(0) }
    key(wipes) {
        SettingsContent(app, voice, onSecureWindowChanged, onWiped = {
            onSecureWindowChanged(app.settings.secureWindow)
            wipes++
        })
    }
}

@Composable
private fun SettingsContent(app: PocketAgentApp, voice: Voice, onSecureWindowChanged: (Boolean) -> Unit, onWiped: () -> Unit) {
    val context = LocalContext.current
    val settings = app.settings
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember(refresh) { mutableStateOf(app.secrets.hasApiKey()) }
    var model by remember { mutableStateOf(settings.model) }
    var effort by remember { mutableStateOf(settings.effort) }
    var instructions by remember { mutableStateOf(settings.customInstructions) }
    var confirmWipe by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    fun open(intent: Intent) = runCatching { context.startActivity(intent) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        BridgeSection(app)

        Heading("Claude API (optional standalone mode)")
        Text(
            "Lets Pocket Agent's own chat, memory and automations run without your computer. Billed per token through a Claude Console API key.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("API key saved, encrypted by the phone's Keystore.", Modifier.weight(1f))
                TextButton(onClick = {
                    app.secrets.clearApiKey()
                    app.factory.forgetClient()
                    hasKey = false
                }) { Text("Remove") }
            }
        } else {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("Anthropic API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = keyInput.startsWith("sk-ant-"), onClick = {
                app.secrets.putApiKey(keyInput)
                app.factory.forgetClient()
                keyInput = ""
                hasKey = true
            }) { Text("Save key") }
        }
        OutlinedTextField(
            value = model,
            onValueChange = {
                model = it
                settings.model = it
                // Don't leave a level selected that the new model would reject.
                val fitting = dev.pocketagent.Settings.effortFor(it.trim(), effort)
                if (fitting != effort) {
                    effort = fitting
                    settings.effort = fitting
                }
            },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Effort (higher is more thorough, slower and costs more)", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dev.pocketagent.Settings.effortsFor(model.trim()).forEach { level ->
                FilterChip(selected = effort == level, onClick = {
                    effort = level
                    settings.effort = level
                }, label = { Text(level) })
            }
        }
        OutlinedTextField(
            value = instructions,
            onValueChange = {
                instructions = it
                settings.customInstructions = it
            },
            label = { Text("Standing instructions / persona") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Text("Model, effort and instructions apply from the next new chat. A new key applies right away, and so does switching a capability off; one switched on is available from the next new chat.", style = MaterialTheme.typography.bodySmall)

        Heading("Capabilities")
        ToolGroup.entries.forEach { group ->
            var on by remember { mutableStateOf(settings.isEnabled(group)) }
            SwitchRow(group.label, group.detail, on) {
                on = it
                settings.setEnabled(group, it)
            }
        }

        Heading("Android permissions")
        val missing = remember(refresh) { DATA_PERMISSIONS.filterNot { context.hasPermission(it.first) } }
        DATA_PERMISSIONS.forEach { (permission, label) ->
            val granted = remember(refresh) { context.hasPermission(permission) }
            Text("${if (granted) "✓" else "✗"}  $label", style = MaterialTheme.typography.bodyMedium)
        }
        if (missing.isNotEmpty()) {
            Button(onClick = { permissionLauncher.launch(missing.map { it.first }.toTypedArray()) }) { Text("Grant missing permissions") }
        }
        Text(
            "On GrapheneOS you can grant Contact Scopes or Storage Scopes instead of full access; the agent works with whatever it is allowed to see.",
            style = MaterialTheme.typography.bodySmall,
        )

        Heading("Special access")
        Text(
            "GrapheneOS blocks accessibility and notification access for sideloaded apps until you open App info › ⋮ › Allow restricted settings.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }) { Text("Open App info") }
        val screenOn = remember(refresh) { AgentAccessibilityService.instance != null }
        OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
            Text("Screen control: ${if (screenOn) "on" else "off"}")
        }
        val notificationsOn = remember(refresh) { NotificationCaptureService.connected }
        OutlinedButton(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
            Text("Notification access: ${if (notificationsOn) "on" else "off"}")
        }
        OutlinedButton(onClick = { open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }) {
            Text("Set as default digital assistant")
        }

        Heading("Voice")
        var speak by remember { mutableStateOf(settings.speakReplies) }
        SwitchRow("Speak replies", "Needs a text-to-speech engine, e.g. RHVoice or SherpaTTS", speak) {
            speak = it
            settings.speakReplies = it
        }
        Text(
            when {
                voice.onDeviceRecognition -> "Speech recognition: on-device."
                voice.anyRecognition -> "Speech recognition: an installed recognizer that may use the network."
                else -> "Speech recognition: none installed."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Heading("Privacy")
        var secure by remember { mutableStateOf(settings.secureWindow) }
        SwitchRow("Block screenshots of this app", "Also hides chats from the recents screen", secure) {
            secure = it
            settings.secureWindow = it
            onSecureWindowChanged(it)
        }
        Text(
            "What leaves the phone: your messages to the agent, the tool results it reads (which can include SMS, " +
                "notifications, calendar entries and screenshots), your memory and skill index, and web searches, all " +
                "sent only to Anthropic's API over TLS. There is no other server, analytics, or Google Play Services " +
                "dependency. Everything else stays in this app's private storage.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { confirmWipe = true }) { Text("Wipe all agent data") }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe everything?") },
            text = { Text("Deletes the API key, settings, memory, skills, past chats, automations and workspace files. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    app.wipeEverything()
                    confirmWipe = false
                    onWiped()
                }) { Text("Wipe") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
