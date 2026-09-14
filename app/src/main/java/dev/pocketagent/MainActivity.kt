package dev.pocketagent

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.pocketagent.ui.AppRoot
import dev.pocketagent.ui.Voice

class MainActivity : ComponentActivity() {
    private lateinit var voice: Voice

    /** Set when opened through the assistant gesture, so the chat starts listening right away. */
    private val assistLaunch = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PocketAgentApp
        applySecureWindow(app.settings.secureWindow)
        voice = Voice(this)
        assistLaunch.value = intent?.action == Intent.ACTION_ASSIST
        setContent {
            AppRoot(app = app, voice = voice, assistLaunch = assistLaunch, onSecureWindowChanged = ::applySecureWindow)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) assistLaunch.value = true
    }

    override fun onResume() {
        super.onResume()
        (application as PocketAgentApp).approvals.uiVisible = true
    }

    override fun onPause() {
        (application as PocketAgentApp).approvals.uiVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        voice.shutdown()
        super.onDestroy()
    }

    private fun applySecureWindow(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
