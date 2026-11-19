package dev.pocketagent.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.pocketagent.PocketAgentApp

private enum class Tab(val label: String, val glyph: String) {
    CHAT("Chat", "💬"),
    AUTOMATIONS("Automations", "⏰"),
    MEMORY("Memory", "🧠"),
    SETTINGS("Settings", "⚙️"),
}

@Composable
fun AppRoot(app: PocketAgentApp, voice: Voice, assistLaunch: MutableState<Boolean>, onSecureWindowChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableStateOf(if (app.secrets.hasApiKey()) Tab.CHAT else Tab.SETTINGS) }
        if (assistLaunch.value) tab = Tab.CHAT
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Text(t.glyph) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            // Each tab keeps its saveable state (a half-typed message, say) while another tab is showing.
            val tabState = rememberSaveableStateHolder()
            Box(Modifier.padding(padding)) {
                tabState.SaveableStateProvider(tab.name) {
                    when (tab) {
                        Tab.CHAT -> ChatScreen(app, voice, assistLaunch)
                        Tab.AUTOMATIONS -> AutomationsScreen(app)
                        Tab.MEMORY -> MemoryScreen(app)
                        Tab.SETTINGS -> SettingsScreen(app, voice, onSecureWindowChanged)
                    }
                }
            }
        }
        ApprovalDialog(app)
    }
}
