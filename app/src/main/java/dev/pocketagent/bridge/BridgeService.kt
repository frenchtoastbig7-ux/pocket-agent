package dev.pocketagent.bridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.device.Notifications
import dev.pocketagent.util.Times
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the [BridgeServer] running in the foreground, bound only to the VPN address (or loopback for USB), and
 * re-binds when the VPN connects, disconnects or changes address.
 */
class BridgeService : Service() {

    sealed interface Status {
        data object Stopped : Status
        data class Waiting(val reason: String) : Status
        data class Running(val host: String, val port: Int) : Status
    }

    companion object {
        private const val ACTION_STOP = "dev.pocketagent.BRIDGE_STOP"
        private const val APPROVAL_TIMEOUT_MS = 120_000L

        val status = MutableStateFlow<Status>(Status.Stopped)
        val activity = MutableStateFlow<List<String>>(emptyList())

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, BridgeService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }

        fun stopIntent(context: Context): Intent = Intent(context, BridgeService::class.java).setAction(ACTION_STOP)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rebindLock = Mutex()

    /** Held while a listener is published or torn down, so one started during onDestroy can't outlive the service. */
    private val lifecycle = Any()
    private var destroyed = false
    private var server: BridgeServer? = null
    private var boundHost: String? = null
    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = rebind()
        override fun onLost(network: Network) = rebind()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = rebind()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PocketAgentApp
        runCatching {
            startForeground(Notifications.BRIDGE_ID, Notifications.bridge(this, "Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        if (intent?.action == ACTION_STOP) {
            app.settings.bridgeEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!callbackRegistered) {
            // The default request excludes VPNs; drop NOT_VPN so Tailscale's network is reported too.
            val request = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, networkCallback)
            callbackRegistered = true
        }
        rebind(force = true)
        return START_NOT_STICKY
    }

    private fun rebind(force: Boolean = false) {
        scope.launch {
            rebindLock.withLock {
                val app = application as PocketAgentApp
                val port = app.settings.bridgePort
                val host = if (app.settings.bridgeBind == "loopback") VpnAddresses.LOOPBACK
                else VpnAddresses.candidates(this@BridgeService).firstOrNull()?.address
                if (!force && host == boundHost && server != null) return@withLock

                synchronized(lifecycle) {
                    server?.stop()
                    server = null
                    boundHost = null
                }

                if (host == null) {
                    status.value = Status.Waiting("No VPN connection. Turn on Tailscale on this phone.")
                    Notifications.updateBridge(this@BridgeService, "Waiting for Tailscale")
                    return@withLock
                }

                val candidate = BridgeServer(
                    tools = app.factory.bridgeTools(),
                    token = app.secrets.bridgeToken(),
                    host = host,
                    port = port,
                    isEnabled = app.settings::isEnabled,
                    approver = { tool, summary ->
                        app.approvals.request(
                            title = "Claude Code wants to ${tool.name.replace('_', ' ')}",
                            summary = summary,
                            forTaskLabel = if (tool.alwaysConfirm) null else "Allow 15 min",
                            timeoutMs = APPROVAL_TIMEOUT_MS,
                        )
                    },
                    onActivity = { line ->
                        activity.update { (listOf("${Times.fmt(System.currentTimeMillis()).substringAfterLast(' ')}  $line") + it).take(30) }
                    },
                )
                try {
                    candidate.start()
                } catch (e: Exception) {
                    status.value = Status.Waiting("Couldn't listen on $host:$port (${e.message})")
                    Notifications.updateBridge(this@BridgeService, "Couldn't start")
                    return@withLock
                }
                synchronized(lifecycle) {
                    if (destroyed) {
                        candidate.stop()
                        return@withLock
                    }
                    server = candidate
                    boundHost = host
                    status.value = Status.Running(host, port)
                }
                Notifications.updateBridge(this@BridgeService, "Listening on $host:$port")
            }
        }
    }

    override fun onDestroy() {
        if (callbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        }
        synchronized(lifecycle) {
            destroyed = true
            server?.stop()
            server = null
            status.value = Status.Stopped
        }
        scope.cancel()
        super.onDestroy()
    }
}
