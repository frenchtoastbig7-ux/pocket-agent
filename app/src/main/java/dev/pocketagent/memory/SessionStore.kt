package dev.pocketagent.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Past conversations as plain-text turns in an on-device SQLite FTS4 index, so the agent can recall earlier chats
 * with session_search. Android's framework SQLite ships FTS4 (not FTS5), and no native library is needed.
 */
class SessionStore(context: Context) : SQLiteOpenHelper(context, "sessions.db", null, 1) {

    data class Session(val id: Long, val title: String, val startedAt: Long, val source: String)
    data class Turn(val role: String, val text: String, val createdAt: Long)
    data class Hit(val sessionId: Long, val title: String, val startedAt: Long, val role: String, val snippet: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL DEFAULT '', " +
                "started_at INTEGER NOT NULL, source TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE turns USING fts4(session_id, role, text, created_at, " +
                "notindexed=session_id, notindexed=role, notindexed=created_at)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun create(source: String, title: String): Long = writableDatabase.insert("sessions", null, ContentValues().apply {
        put("title", title.take(80))
        put("started_at", System.currentTimeMillis())
        put("source", source)
    })

    fun addTurn(sessionId: Long, role: String, text: String) {
        if (text.isBlank()) return
        writableDatabase.insert("turns", null, ContentValues().apply {
            put("session_id", sessionId)
            put("role", role)
            put("text", text)
            put("created_at", System.currentTimeMillis())
        })
    }

    fun search(query: String, limit: Int): List<Hit> {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query).map { "\"${it.value}\"" }.take(8).toList()
        if (words.isEmpty()) return emptyList()
        // All words first; if that finds nothing, any word.
        return runSearch(words.joinToString(" "), limit).ifEmpty { runSearch(words.joinToString(" OR "), limit) }
    }

    private fun runSearch(match: String, limit: Int): List<Hit> {
        val sql = "SELECT turns.session_id, sessions.title, sessions.started_at, turns.role, " +
            "snippet(turns, '[', ']', '…', 2, 32) FROM turns JOIN sessions ON sessions.id = turns.session_id " +
            "WHERE turns MATCH ? ORDER BY turns.created_at DESC LIMIT ?"
        return readableDatabase.rawQuery(sql, arrayOf(match, limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(Hit(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4)))
            }
        }
    }

    fun sessions(limit: Int = 200): List<Session> =
        readableDatabase.rawQuery("SELECT id, title, started_at, source FROM sessions ORDER BY started_at DESC LIMIT ?", arrayOf(limit.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(Session(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3))) } }

    fun turns(sessionId: Long): List<Turn> =
        readableDatabase.rawQuery("SELECT role, text, created_at FROM turns WHERE session_id = ? ORDER BY created_at", arrayOf(sessionId.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(Turn(c.getString(0), c.getString(1), c.getLong(2))) } }

    fun delete(sessionId: Long) {
        writableDatabase.delete("turns", "session_id = ?", arrayOf(sessionId.toString()))
        writableDatabase.delete("sessions", "id = ?", arrayOf(sessionId.toString()))
    }

    fun deleteAll() {
        writableDatabase.execSQL("DELETE FROM turns")
        writableDatabase.execSQL("DELETE FROM sessions")
        writableDatabase.execSQL("VACUUM")
    }
}
