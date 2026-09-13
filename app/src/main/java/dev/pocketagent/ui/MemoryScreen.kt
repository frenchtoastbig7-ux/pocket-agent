package dev.pocketagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.memory.MemoryStore
import dev.pocketagent.memory.SessionStore
import dev.pocketagent.memory.SkillStore
import dev.pocketagent.util.Times

/** Everything the agent remembers, visible and deletable by the user. */
@Composable
fun MemoryScreen(app: PocketAgentApp) {
    var refresh by remember { mutableIntStateOf(0) }
    val userEntries = remember(refresh) { app.memory.entries(MemoryStore.Target.USER) }
    val memoryEntries = remember(refresh) { app.memory.entries(MemoryStore.Target.MEMORY) }
    val skills = remember(refresh) { app.skills.list() }
    val sessions = remember(refresh) { app.sessions.sessions() }
    var openSkill by remember { mutableStateOf<SkillStore.Skill?>(null) }
    var openSession by remember { mutableStateOf<SessionStore.Session?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Memory", style = MaterialTheme.typography.titleLarge)
            Text(
                "What the agent keeps between chats. All of it is stored only on this phone; it is sent to Claude as part of each chat.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item { Section("About you (USER.md)") }
        if (userEntries.isEmpty()) item { Empty() }
        items(userEntries) { entry ->
            EntryRow(entry) {
                app.memory.setEntries(MemoryStore.Target.USER, userEntries - entry)
                refresh++
            }
        }

        item { Section("Agent notes (MEMORY.md)") }
        if (memoryEntries.isEmpty()) item { Empty() }
        items(memoryEntries) { entry ->
            EntryRow(entry) {
                app.memory.setEntries(MemoryStore.Target.MEMORY, memoryEntries - entry)
                refresh++
            }
        }

        item { Section("Skills") }
        if (skills.isEmpty()) item { Empty() }
        items(skills, key = { "skill-" + it.name }) { skill ->
            Card(Modifier.fillMaxWidth().clickable { openSkill = skill }) {
                Column(Modifier.padding(12.dp)) {
                    Text(skill.name, style = MaterialTheme.typography.titleSmall)
                    Text(skill.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Section("Past chats", Modifier.weight(1f))
                if (sessions.isNotEmpty()) TextButton(onClick = { confirmClearHistory = true }) { Text("Delete all") }
            }
        }
        if (sessions.isEmpty()) item { Empty() }
        items(sessions, key = { "session-" + it.id }) { session ->
            Row(Modifier.fillMaxWidth().clickable { openSession = session }.padding(vertical = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(session.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.bodyMedium)
                    Text("${Times.fmt(session.startedAt)} · ${session.source}", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
    }

    openSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { openSkill = null },
            title = { Text(skill.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { SelectionContainer { Text(skill.description + "\n\n" + skill.body) } } },
            confirmButton = { TextButton(onClick = { openSkill = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    app.skills.delete(skill.name)
                    openSkill = null
                    refresh++
                }) { Text("Delete") }
            },
        )
    }

    openSession?.let { session ->
        val turns = remember(session.id) { app.sessions.turns(session.id) }
        AlertDialog(
            onDismissRequest = { openSession = null },
            title = { Text(session.title.ifBlank { "Chat" }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    turns.forEach { turn ->
                        Text(if (turn.role == "user") "You" else "Agent", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        SelectionContainer { Text(turn.text) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { openSession = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    app.sessions.delete(session.id)
                    openSession = null
                    refresh++
                }) { Text("Delete") }
            },
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Delete all past chats?") },
            text = { Text("The agent will no longer be able to search them. Memory and skills are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    app.sessions.deleteAll()
                    confirmClearHistory = false
                    refresh++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Empty() {
    Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EntryRow(text: String, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(Modifier.weight(1f)) { Text(text, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = onDelete) { Text("Forget") }
    }
}
