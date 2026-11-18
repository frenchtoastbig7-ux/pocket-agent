package dev.pocketagent

import android.app.Application
import dev.pocketagent.agent.AgentFactory
import dev.pocketagent.bridge.BridgeService
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
        scheduler.cancelAll()
        schedules.clear()
        memory.clear()
        skills.clear()
        sessions.deleteAll()
        workspace.root.listFiles()?.forEach { it.deleteRecursively() }
        factory.forgetClient()
        secrets.wipe()
        settings.clear()
    }
}
