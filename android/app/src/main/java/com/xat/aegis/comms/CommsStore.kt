package com.xat.aegis.comms

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local cache of the conversation, so the COMMS tab opens instantly and works
 * offline for reading. Message bodies are encrypted under the comms Keystore
 * key; peers, timestamps and status are plain so the thread list can be queried.
 *
 * The relay's copy is authoritative; this only ever holds what a sync returned.
 */
class CommsStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                seq INTEGER NOT NULL,
                direction TEXT NOT NULL,
                peer TEXT NOT NULL,
                body_enc BLOB NOT NULL,
                media TEXT NOT NULL,
                status TEXT NOT NULL,
                error TEXT,
                ts INTEGER NOT NULL,
                updated INTEGER NOT NULL,
                read INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX messages_peer_ts ON messages (peer, ts)")
        db.execSQL("CREATE INDEX messages_seq ON messages (seq)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the only schema so far; a future change migrates in place.
    }

    /**
     * Inserts or replaces each message. Rows that already exist keep their read
     * flag; an inbound row that is new is unread. Returns the messages that were
     * not in the cache before, for notifications.
     */
    fun upsert(messages: List<SmsMessage>): List<SmsMessage> {
        if (messages.isEmpty()) return emptyList()
        val fresh = ArrayList<SmsMessage>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in messages) {
                val existingRead = db.rawQuery("SELECT read FROM messages WHERE id = ?", arrayOf(m.id)).use { c ->
                    if (c.moveToFirst()) c.getInt(0) != 0 else null
                }
                if (existingRead == null) fresh += m
                val values = ContentValues().apply {
                    put("id", m.id)
                    put("seq", m.seq)
                    put("direction", m.direction.name)
                    put("peer", m.peer)
                    put("body_enc", CommsCrypto.encryptString(m.body))
                    put("media", JSONArray(m.media.map { JSONObject().put("url", it.url).put("contentType", it.contentType) }).toString())
                    put("status", m.status)
                    put("error", m.error)
                    put("ts", m.ts)
                    put("updated", m.updated)
                    // Outbound messages are the owner's own, so never "unread".
                    put("read", if (existingRead ?: (m.direction == Direction.OUT)) 1 else 0)
                }
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return fresh
    }

    fun threads(): List<Thread> {
        val db = readableDatabase
        // The newest row per peer, plus counts.
        val sql = """
            SELECT m.*, agg.unread, agg.total FROM messages m
            JOIN (
                SELECT peer, MAX(ts) AS max_ts, SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END) AS unread, COUNT(*) AS total
                FROM messages GROUP BY peer
            ) agg ON agg.peer = m.peer AND agg.max_ts = m.ts
            GROUP BY m.peer
            ORDER BY m.ts DESC
        """.trimIndent()
        return db.rawQuery(sql, null).use { c ->
            val out = ArrayList<Thread>()
            while (c.moveToNext()) {
                val message = readMessage(c) ?: continue
                out += Thread(
                    peer = message.peer,
                    lastMessage = message,
                    unread = c.getInt(c.getColumnIndexOrThrow("unread")),
                    count = c.getInt(c.getColumnIndexOrThrow("total"))
                )
            }
            out
        }
    }

    fun messages(peer: String): List<SmsMessage> {
        val db = readableDatabase
        return db.query("messages", null, "peer = ?", arrayOf(peer), null, null, "ts ASC, seq ASC").use { c ->
            val out = ArrayList<SmsMessage>()
            while (c.moveToNext()) readMessage(c)?.let { out += it }
            out
        }
    }

    fun message(id: String): SmsMessage? =
        readableDatabase.query("messages", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) readMessage(c) else null
        }

    fun markRead(peer: String) {
        writableDatabase.execSQL("UPDATE messages SET read = 1 WHERE peer = ? AND read = 0", arrayOf(peer))
    }

    fun unreadCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE read = 0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun clear() {
        writableDatabase.delete("messages", null, null)
    }

    private fun readMessage(c: Cursor): SmsMessage? {
        val body = runCatching {
            CommsCrypto.decryptString(c.getBlob(c.getColumnIndexOrThrow("body_enc")))
        }.getOrElse { return null }
        val media = ArrayList<MediaItem>()
        runCatching {
            val arr = JSONArray(c.getString(c.getColumnIndexOrThrow("media")))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                media += MediaItem(o.getString("url"), o.optString("contentType", "application/octet-stream"))
            }
        }
        return SmsMessage(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            seq = c.getLong(c.getColumnIndexOrThrow("seq")),
            direction = runCatching { Direction.valueOf(c.getString(c.getColumnIndexOrThrow("direction"))) }
                .getOrDefault(Direction.IN),
            peer = c.getString(c.getColumnIndexOrThrow("peer")),
            body = body,
            media = media,
            status = c.getString(c.getColumnIndexOrThrow("status")),
            error = c.getColumnIndexOrThrow("error").let { if (c.isNull(it)) null else c.getString(it) },
            ts = c.getLong(c.getColumnIndexOrThrow("ts")),
            updated = c.getLong(c.getColumnIndexOrThrow("updated")),
            read = c.getInt(c.getColumnIndexOrThrow("read")) != 0
        )
    }

    private companion object {
        const val DB_NAME = "comms.db"
        const val DB_VERSION = 1
    }
}
