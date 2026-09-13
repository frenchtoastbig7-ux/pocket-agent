package dev.pocketagent

import android.content.Context
import dev.pocketagent.agent.ToolGroup

/** Non-secret preferences. The API key lives in [SecretStore]. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL)!!
        set(value) = prefs.edit().putString("model", value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /** One of low, medium, high, xhigh, max. */
    var effort: String
        get() = prefs.getString("effort", "high")!!
        set(value) = prefs.edit().putString("effort", value).apply()

    /** Free-form persona / standing instructions, like Hermes' SOUL.md. */
    var customInstructions: String
        get() = prefs.getString("custom_instructions", "")!!
        set(value) = prefs.edit().putString("custom_instructions", value).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", false)
        set(value) = prefs.edit().putBoolean("speak_replies", value).apply()

    /** FLAG_SECURE on our window: no screenshots, screen recording, or recents thumbnail of chats. */
    var secureWindow: Boolean
        get() = prefs.getBoolean("secure_window", true)
        set(value) = prefs.edit().putBoolean("secure_window", value).apply()

    fun isEnabled(group: ToolGroup): Boolean = prefs.getBoolean("group_${group.name}", true)
    fun setEnabled(group: ToolGroup, enabled: Boolean) = prefs.edit().putBoolean("group_${group.name}", enabled).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    }
}
