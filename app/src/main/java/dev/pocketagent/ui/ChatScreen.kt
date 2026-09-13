package dev.pocketagent.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.pocketagent.ChatItem
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.agent.ApprovalDecision
import dev.pocketagent.agent.ToolStatus

@Composable
fun ChatScreen(app: PocketAgentApp, voice: Voice, assistLaunch: MutableState<Boolean>) {
    val controller = app.controller
    val items by controller.items.collectAsState()
    val running by controller.running.collectAsState()
    val pending by controller.pending.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun startListening() {
        listening = true
        voice.listen(
            onPartial = { input = it },
            onFinal = { text ->
                listening = false
                if (text.isNotBlank()) {
                    controller.send(text)
                    input = ""
                }
            },
            onError = { message ->
                listening = false
                input = message
            },
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening()
    }

    LaunchedEffect(items.size, items.lastOrNull()?.text?.length) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }
    LaunchedEffect(Unit) {
        controller.finishedReplies.collect { if (app.settings.speakReplies) voice.speak(it) }
    }
    LaunchedEffect(assistLaunch.value) {
        if (assistLaunch.value) {
            assistLaunch.value = false
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Pocket Agent", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { controller.newChat() }, enabled = items.isNotEmpty()) { Text("New chat") }
        }

        if (!app.secrets.hasApiKey()) {
            Text(
                "Add your Anthropic API key in Settings to start.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (items.isEmpty()) item { EmptyHint() }
            items(items, key = { it.id }) { ChatRow(it) }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (listening) "Listening…" else "Ask or tell it what to do") },
                maxLines = 6,
            )
            Column(Modifier.padding(start = 6.dp)) {
                if (running) {
                    Button(onClick = { controller.stop() }) { Text("Stop") }
                } else if (input.isBlank()) {
                    FilledTonalButton(onClick = {
                        if (listening) {
                            voice.stopListening()
                            listening = false
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) { Text(if (listening) "Done" else "🎤") }
                } else {
                    Button(onClick = {
                        controller.send(input)
                        input = ""
                    }) { Text("Send") }
                }
            }
        }
    }

    pending?.let { approval ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(approval.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(approval.summary) }
                }
            },
            confirmButton = {
                Row {
                    if (approval.allowForTask) {
                        TextButton(onClick = { controller.resolveApproval(ApprovalDecision.FOR_TASK) }) { Text("Allow for task") }
                    }
                    TextButton(onClick = { controller.resolveApproval(ApprovalDecision.ONCE) }) { Text("Allow") }
                }
            },
            dismissButton = { TextButton(onClick = { controller.resolveApproval(ApprovalDecision.DENY) }) { Text("Deny") } },
        )
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Try:", style = MaterialTheme.typography.titleMedium)
        listOf(
            "What's on my calendar tomorrow, and did I miss anything important today?",
            "Text Sam that I'm running 10 minutes late",
            "Every weekday at 7:30, summarize my calendar and overnight notifications",
            "Open Signal and summarize the unread messages in the family group",
        ).forEach {
            Text("“$it”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    when (item.kind) {
        ChatItem.Kind.USER -> Bubble(item.text, alignEnd = true, color = MaterialTheme.colorScheme.primaryContainer)
        ChatItem.Kind.ASSISTANT -> Bubble(item.text, alignEnd = false, color = MaterialTheme.colorScheme.surfaceVariant)
        ChatItem.Kind.THINKING -> Text(
            item.text.takeLast(280).let { if (item.text.length > 280) "…$it" else it },
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        ChatItem.Kind.TOOL -> {
            val mark = when (item.status) {
                ToolStatus.RUNNING -> "…"
                ToolStatus.OK -> "✓"
                ToolStatus.ERROR -> "✗"
                ToolStatus.DENIED -> "⊘ denied"
                null -> ""
            }
            Column {
                Text("🔧 ${item.text}  $mark", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                if (item.detail.isNotEmpty()) {
                    Text(item.detail.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ChatItem.Kind.NOTICE -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Bubble(text: String, alignEnd: Boolean, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(color = color, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 340.dp)) {
            SelectionContainer { Text(text, modifier = Modifier.padding(12.dp)) }
        }
    }
}
