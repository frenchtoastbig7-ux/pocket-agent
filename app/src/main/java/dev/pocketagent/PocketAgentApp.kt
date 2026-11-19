package dev.pocketagent

import android.app.Application
import dev.pocketagent.agent.AgentFactory
import android.app.NotificationManager
import android.content.Intent
import dev.pocketagent.bridge.BridgeService
import dev.pocketagent.device.AgentRunService
import dev.pocketagent.device.Notifications
import dev.pocketagent.memory.MemoryStore
import dev.pocketagent.memory.SessionStore
import dev.pocketagent.memory.SkillStore
import dev.pocketagent.schedule.ScheduleStore
import dev.pocketagent.schedule.Scheduler
import dev.pocketagent.tools.Workspace

class PocketAgentApp : Application() {
    lateinit var settings: Settings
    lateinit var secrets: SecretStore
    lateinit var approvals: ApprovalBroker
    lateinit var memory: MemoryStore
    lateinit var skills: SkillStore
    lateinit var sessions: SessionStore
    lateinit var schedules: ScheduleStore
    lateinit var scheduler: Scheduler
    lateinit var workspace: Workspace
    lateinit var factory: AgentFactory
    lateinit var controller: ChatController

    /** Bumped by [wipeEverything]. A run that started before a wipe saves through [ifCurrent] and so writes nothing afterwards. */
    @Volatile
    var dataEpoch = 0
        private set

    /** Counts wipes for the UI, which drops its saved screen state (a half-typed message, say) when it changes. */
    val wipes = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val dataLock = Any()

    /**
     * Runs [block] unless a wipe has happened since [epoch] was read; returns null then. The wipe holds the same lock,
     * so a write that started before it finishes first and is deleted, and one that comes after it never happens.
     */
    fun <T : Any> ifCurrent(epoch: Int, block: () -> T): T? = synchronized(dataLock) {
        if (dataEpoch != epoch) null else block()
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        secrets = SecretStore(this)
        approvals = ApprovalBroker(this)
        memory = MemoryStore(this)
        skills = SkillStore(this)
        sessions = SessionStore(this)
        schedules = ScheduleStore(this)
        scheduler = Scheduler(this, schedules)
        workspace = Workspace(this)
        factory = AgentFactory(this)
        controller = ChatController(this)
        Notifications.createChannels(this)
        // Re-register alarms in case the app was force-stopped (which clears them) since they were set.
        scheduler.rearmAll()
    }

    /** Removes everything: API key, bridge token, settings, memory, skills, chat history, automations, workspace. */
    fun wipeEverything() {
        BridgeService.stop(this)
        controller.newChat()
        // Stops running automations too; whatever they still try to save goes through ifCurrent and is refused.
        runCatching { startService(Intent(this, AgentRunService::class.java).setAction(AgentRunService.ACTION_STOP)) }
        synchronized(dataLock) {
            dataEpoch++
            scheduler.wipe()
            memory.clear()
            skills.clear()
            sessions.deleteAll()
            workspace.root.listFiles()?.forEach { it.deleteRecursively() }
            factory.forgetClient()
            secrets.wipe()
            settings.clear()
            // Automation reports and approval prompts already in the notification shade are copies of wiped data.
            getSystemService(NotificationManager::class.java).cancelAll()
        }
        wipes.value++
    }
}
