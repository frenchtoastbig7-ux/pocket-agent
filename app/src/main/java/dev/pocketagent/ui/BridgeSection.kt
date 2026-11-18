package dev.pocketagent.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.bridge.BridgeService
import dev.pocketagent.bridge.VpnAddresses

/** Settings for using Pocket Agent's phone tools from Claude Code (the Claude subscription path). */
@Composable
fun BridgeSection(app: PocketAgentApp) {
    val context = LocalContext.current
    val settings = app.settings
    val status by BridgeService.status.collectAsState()
    val activity by BridgeService.activity.collectAsState()
    var enabled by remember { mutableStateOf(settings.bridgeEnabled) }
    var bind by remember { mutableStateOf(settings.bridgeBind) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirmRotate by remember { mutableStateOf(false) }
    val vpn = remember(refresh, status) { VpnAddresses.candidates(context) }

    fun restartIfRunning() {
        if (enabled) {
            BridgeService.stop(context)
            BridgeService.start(context)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Claude Code bridge (uses your Claude subscription)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Claude Code on your computer does the thinking under your Pro or Max plan, and uses this phone's tools " +
                "over your Tailscale network. Chat with it from the Claude app with Remote Control. Pocket Agent " +
                "never sees your Claude login; actions still need your approval here.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Run bridge", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                settings.bridgeEnabled = it
                if (it) BridgeService.start(context) else BridgeService.stop(context)
            })
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = bind == "vpn", onClick = {
                bind = "vpn"
                settings.bridgeBind = "vpn"
                restartIfRunning()
            }, label = { Text("Tailscale / VPN") })
            FilterChip(selected = bind == "loopback", onClick = {
                bind = "loopback"
                settings.bridgeBind = "loopback"
                restartIfRunning()
            }, label = { Text("USB (adb forward)") })
        }

        Text(
            when (val s = status) {
                BridgeService.Status.Stopped -> "Status: off"
                is BridgeService.Status.Waiting -> "Status: ${s.reason}"
                is BridgeService.Status.Running -> "Status: listening on ${s.host}:${s.port}"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (bind == "vpn") {
            Text(
                if (vpn.isEmpty()) "No VPN address found. Install Tailscale on this phone and connect it."
                else "VPN: " + vpn.joinToString { "${it.address} (${it.label})" },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val host = (status as? BridgeService.Status.Running)?.host ?: if (bind == "loopback") VpnAddresses.LOOPBACK else vpn.firstOrNull()?.address
        if (host != null) {
            val command = "claude mcp add --transport http --scope user phone http://$host:${settings.bridgePort}/mcp " +
                "--header \"Authorization: Bearer ${app.secrets.bridgeToken()}\""
            Text("Run this once on your computer:", style = MaterialTheme.typography.bodySmall)
            if (bind == "loopback") {
                Text("adb forward tcp:${settings.bridgePort} tcp:${settings.bridgePort}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text(command.replace(app.secrets.bridgeToken(), "••••••••"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    val clip = ClipData.newPlainText("Claude Code command", command).apply {
                        // Keeps the token out of the clipboard preview.
                        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                }) { Text("Copy command") }
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, command), "Send to your computer")
                    )
                }) { Text("Share") }
                TextButton(onClick = { confirmRotate = true }) { Text("New token") }
            }
        }

        if (activity.isNotEmpty()) {
            Text("Recent bridge calls", style = MaterialTheme.typography.labelLarge)
            activity.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("Create a new bridge token?") },
            text = { Text("Claude Code stops being able to connect until you run the new command on your computer (remove the old server first with: claude mcp remove phone).") },
            confirmButton = {
                TextButton(onClick = {
                    app.secrets.rotateBridgeToken()
                    confirmRotate = false
                    refresh++
                    restartIfRunning()
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } },
        )
    }
}
