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

    /** Bumped by [wipeEverything]. A run that started before a wipe checks it and writes nothing afterwards. */
    @Volatile
    var dataEpoch = 0
        private set

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
        dataEpoch++
        BridgeService.stop(this)
        controller.newChat()
        // Stops running automations too; they also check dataEpoch before saving anything.
        runCatching { startService(Intent(this, AgentRunService::class.java).setAction(AgentRunService.ACTION_STOP)) }
        scheduler.cancelAll()
        schedules.clear()
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
}
