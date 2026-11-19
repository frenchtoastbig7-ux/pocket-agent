package dev.pocketagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocketagent.memory.MemoryStore
import dev.pocketagent.memory.SessionStore
import dev.pocketagent.schedule.ScheduleStore
import dev.pocketagent.schedule.ScheduledTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The on-disk stores, on a real device. Each test removes what it creates. */
@RunWith(AndroidJUnit4::class)
class StoresDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aTurnForADeletedSessionIsNotSaved() {
        val store = SessionStore(context)
        val id = store.create("test", "stores device test")
        try {
            assertTrue(store.addTurn(id, "user", "zebrafinch one"))
            assertEquals(1, store.turns(id).size)
            store.delete(id)
            assertTrue("deleting a chat must delete its turns", store.readableDatabase
                .rawQuery("SELECT 1 FROM turns WHERE turns MATCH 'zebrafinch'", null).use { !it.moveToFirst() })
            assertFalse(store.exists(id))
            assertFalse(store.addTurn(id, "assistant", "zebrafinch two"))
            assertTrue(store.turns(id).isEmpty())
        } finally {
            store.delete(id)
        }
    }

    @Test
    fun searchLeavesOutTheCurrentChatBeforeTheLimit() {
        val store = SessionStore(context)
        val old = store.create("test", "older chat")
        val current = store.create("test", "current chat")
        try {
            store.addTurn(old, "user", "quokkaword in the older chat")
            repeat(15) { store.addTurn(current, "user", "quokkaword again $it") }
            val hits = store.search("quokkaword", limit = 10, excludeSession = current)
            assertEquals(listOf(old), hits.map { it.sessionId })
        } finally {
            store.delete(old)
            store.delete(current)
        }
    }

    @Test
    fun memoryReplaceNeedsContentAndForgetRemovesOnlyThatEntry() {
        val store = MemoryStore(context)
        val target = MemoryStore.Target.MEMORY
        val before = store.entries(target)
        try {
            store.setEntries(target, listOf("alpha note", "beta note"))
            assertThrows(IllegalArgumentException::class.java) { store.replace(target, "alpha", "  ") }
            assertEquals(listOf("alpha note", "beta note"), store.entries(target))
            // The screen's list is older than the file: an entry added since must survive the forget.
            store.add(target, "gamma note")
            store.forget(target, "alpha note")
            assertEquals(listOf("beta note", "gamma note"), store.entries(target))
        } finally {
            store.setEntries(target, before)
        }
    }

    @Test
    fun aResultUpdateKeepsLaterEditsAndNeverRecreatesADeletedTask() {
        val store = ScheduleStore(context)
        val task = ScheduledTask(title = "t", prompt = "p", spec = "daily 07:00")
        try {
            store.put(task)
            store.put(store.get(task.id)!!.copy(paused = true))
            val updated = store.update(task.id) { it.copy(lastResult = "done") }
            assertEquals(true, updated?.paused)
            assertEquals("done", store.get(task.id)?.lastResult)
            store.remove(task.id)
            assertNull(store.update(task.id) { it.copy(lastResult = "late") })
            assertNull(store.get(task.id))
        } finally {
            store.remove(task.id)
        }
    }
}
