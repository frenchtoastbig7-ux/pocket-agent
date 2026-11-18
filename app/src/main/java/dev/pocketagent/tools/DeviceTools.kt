package dev.pocketagent.tools

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.fasterxml.jackson.databind.JsonNode
import dev.pocketagent.agent.AgentTool
import dev.pocketagent.agent.Risk
import dev.pocketagent.agent.Schema
import dev.pocketagent.agent.ToolGroup
import dev.pocketagent.agent.ToolOutput
import dev.pocketagent.agent.str
import dev.pocketagent.device.AgentAccessibilityService
import dev.pocketagent.device.NotificationCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class DeviceStatusTool(private val ctx: Context) : AgentTool(
    name = "device_status",
    description = "Get the current local date/time and time zone, battery level, network type, and which agent " +
        "capabilities (screen control, notification access) are enabled.",
    group = ToolGroup.DEVICE,
    risk = Risk.READ,
) {
    override val properties = emptyMap<String, Map<String, Any>>()

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now()
        val battery = ctx.getSystemService(BatteryManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "offline"
            else -> listOfNotNull(
                "wifi".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                "cellular".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                "vpn".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
                "ethernet".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
            ).joinToString("+").ifEmpty { "other" }
        }
        ToolOutput(
            """
            Local time: ${now.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm"))} (${now.zone.id}, UTC${now.offset})
            Battery: ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%${if (battery.isCharging) ", charging" else ""}
            Network: $network
            Screen control: ${if (AgentAccessibilityService.instance != null) "enabled" else "disabled"}
            Notification access: ${if (NotificationCaptureService.connected) "enabled" else "disabled"}
            """.trimIndent()
        )
    }
}

private data class LauncherApp(val label: String, val packageName: String)

private fun Context.launcherApps(): List<LauncherApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return packageManager.queryIntentActivities(intent, 0)
        .map { LauncherApp(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

class ListAppsTool(private val ctx: Context) : AgentTool(
    name = "list_apps",
    description = "List apps installed on the phone that have a launcher icon, with their package names.",
    group = ToolGroup.DEVICE,
    risk = Risk.READ,
) {
    override val properties = mapOf("query" to Schema.str("Only apps whose name or package contains this text"))

    override suspend fun run(input: JsonNode): ToolOutput = withContext(Dispatchers.IO) {
        val q = input.str("query")
        val apps = ctx.launcherApps().filter { q == null || it.label.contains(q, true) || it.packageName.contains(q, true) }
        ToolOutput(if (apps.isEmpty()) "No matching apps." else apps.joinToString("\n") { "${it.label} (${it.packageName})" })
    }
}

class OpenAppTool(private val ctx: Context) : AgentTool(
    name = "open_app",
    description = "Bring an installed app to the foreground by name or package. Use it before read_screen/tap when " +
        "the user wants something done inside another app.",
    group = ToolGroup.DEVICE,
    risk = Risk.WRITE,
) {
    override val properties = mapOf("app" to Schema.str("App name (e.g. \"Signal\") or package name"))
    override val required = listOf("app")

    override fun summarize(input: JsonNode) = "Open app: ${input.str("app")}"

    override suspend fun run(input: JsonNode): ToolOutput {
        val query = input.str("app") ?: return ToolOutput.error("Missing 'app'.")
        val apps = withContext(Dispatchers.IO) { ctx.launcherApps() }
        val app = apps.firstOrNull { it.packageName == query }
            ?: apps.firstOrNull { it.label.equals(query, true) }
            ?: apps.firstOrNull { it.label.contains(query, true) }
            ?: return ToolOutput.error("No installed app matches \"$query\". Call list_apps to see what is installed.")
        val launch = ctx.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return ToolOutput.error("${app.label} can't be launched.")
        // Starting an activity from the background is allowed while our accessibility service is bound.
        runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { return ToolOutput.error("Android blocked opening ${app.label}: ${it.message}") }
        delay(1500)
        return ToolOutput("Opened ${app.label}. Use read_screen or screenshot to see it.")
    }
}
