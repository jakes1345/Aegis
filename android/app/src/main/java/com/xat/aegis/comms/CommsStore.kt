package com.xat.aegis.comms

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Contacts, conversations and the outbox, in the app's private SQLite database.
 * Message bodies are encrypted under the Keystore key; contact keys, numbers,
 * timestamps and status are plain so the lists can be queried.
 */
class CommsStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE contacts (
                number TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                ed25519 TEXT NOT NULL,
                curve25519 TEXT NOT NULL UNIQUE,
                sealing TEXT NOT NULL,
                signature TEXT NOT NULL,
                verified INTEGER NOT NULL DEFAULT 0,
                key_changed INTEGER NOT NULL DEFAULT 0,
                added_ts INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                peer TEXT NOT NULL,
                direction TEXT NOT NULL,
                body_enc BLOB NOT NULL,
                ts INTEGER NOT NULL,
                status TEXT NOT NULL,
                read INTEGER NOT NULL DEFAULT 0,
                error TEXT
            )"""
        )
        db.execSQL("CREATE INDEX messages_peer_ts ON messages (peer, ts)")
        // Envelopes accepted from the relay but not yet processed, so nothing is
        // acknowledged (and deleted on the relay) before it is safely stored.
        db.execSQL(
            """CREATE TABLE seen_envelopes (
                id TEXT PRIMARY KEY,
                ts INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema for the end-to-end module; the Twilio-era
        // tables lived in a database of a different name and are left untouched.
    }

    // ── Contacts ─────────────────────────────────────────────────────────

    fun upsertContact(c: Contact) {
        val values = ContentValues().apply {
            put("number", c.number)
            put("name", c.name)
            put("ed25519", c.ed25519)
            put("curve25519", c.curve25519)
            put("sealing", c.sealing)
            put("signature", c.signature)
            put("verified", if (c.verified) 1 else 0)
            put("key_changed", if (c.keyChanged) 1 else 0)
            put("added_ts", c.addedTs)
        }
        writableDatabase.insertWithOnConflict("contacts", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun contact(number: String): Contact? =
        readableDatabase.query("contacts", null, "number = ?", arrayOf(number), null, null, null).use { c ->
            if (c.moveToFirst()) readContact(c) else null
        }

    fun contactByCurve(curve25519: String): Contact? =
        readableDatabase.query("contacts", null, "curve25519 = ?", arrayOf(curve25519), null, null, null).use { c ->
            if (c.moveToFirst()) readContact(c) else null
        }

    fun contacts(): List<Contact> =
        readableDatabase.query("contacts", null, null, null, null, null, "name COLLATE NOCASE ASC").use { c ->
            val out = ArrayList<Contact>()
            while (c.moveToNext()) out += readContact(c)
            out
        }

    fun deleteContact(number: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", "peer = ?", arrayOf(number))
            db.delete("contacts", "number = ?", arrayOf(number))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ── Messages ─────────────────────────────────────────────────────────

    fun insertMessage(m: ChatMessage) {
        val values = ContentValues().apply {
            put("id", m.id)
            put("peer", m.peer)
            put("direction", m.direction.name)
            put("body_enc", KeystoreBox.encryptString(m.body))
            put("ts", m.ts)
            put("status", m.status)
            put("read", if (m.read) 1 else 0)
            put("error", m.error)
        }
        writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun hasMessage(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    fun setStatus(id: String, status: String, error: String? = null) {
        val values = ContentValues().apply { put("status", status); put("error", error) }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    /** Advances outbound statuses, never backwards (a late "delivered" after "read"). */
    fun advanceStatus(ids: List<String>, status: String) {
        if (ids.isEmpty()) return
        val rank = mapOf("queued" to 0, "sent" to 1, "delivered" to 2, "read" to 3)
        val target = rank[status] ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                val current = db.rawQuery("SELECT status FROM messages WHERE id = ? AND direction = 'OUT'", arrayOf(id)).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: continue
                if ((rank[current] ?: -1) < target) {
                    db.update("messages", ContentValues().apply { put("status", status) }, "id = ?", arrayOf(id))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun messages(peer: String): List<ChatMessage> =
        readableDatabase.query("messages", null, "peer = ?", arrayOf(peer), null, null, "ts ASC").use { c ->
            val out = ArrayList<ChatMessage>()
            while (c.moveToNext()) readMessage(c)?.let { out += it }
            out
        }

    /** Outbound messages still waiting to be sent, oldest first. */
    fun queued(): List<ChatMessage> =
        readableDatabase.query("messages", null, "direction = 'OUT' AND status = 'queued'", null, null, null, "ts ASC").use { c ->
            val out = ArrayList<ChatMessage>()
            while (c.moveToNext()) readMessage(c)?.let { out += it }
            out
        }

    fun threads(): List<ChatThread> {
        val contacts = contacts()
        val db = readableDatabase
        return contacts.map { contact ->
            val last = db.query("messages", null, "peer = ?", arrayOf(contact.number), null, null, "ts DESC", "1").use { c ->
                if (c.moveToFirst()) readMessage(c) else null
            }
            val unread = db.rawQuery(
                "SELECT COUNT(*) FROM messages WHERE peer = ? AND direction = 'IN' AND read = 0", arrayOf(contact.number)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            ChatThread(contact, last, unread)
        }.sortedByDescending { it.lastMessage?.ts ?: it.contact.addedTs }
    }

    /** Marks a conversation read and returns the ids that were unread, for read receipts. */
    fun markRead(peer: String): List<String> {
        val db = writableDatabase
        val ids = db.rawQuery("SELECT id FROM messages WHERE peer = ? AND direction = 'IN' AND read = 0", arrayOf(peer)).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }
        if (ids.isNotEmpty()) db.execSQL("UPDATE messages SET read = 1 WHERE peer = ? AND direction = 'IN' AND read = 0", arrayOf(peer))
        return ids
    }

    fun unreadCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE direction = 'IN' AND read = 0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    // ── Envelope bookkeeping ─────────────────────────────────────────────

    /** Records an envelope id; false when it was already processed. */
    fun markEnvelopeSeen(id: String, ts: Long): Boolean {
        val values = ContentValues().apply { put("id", id); put("ts", ts) }
        val row = writableDatabase.insertWithOnConflict("seen_envelopes", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (row != -1L) {
            writableDatabase.execSQL("DELETE FROM seen_envelopes WHERE ts < ?", arrayOf(System.currentTimeMillis() - 45L * 24 * 3600_000L))
        }
        return row != -1L
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete("messages", null, null)
        db.delete("contacts", null, null)
        db.delete("seen_envelopes", null, null)
    }

    private fun readContact(c: Cursor) = Contact(
        number = c.getString(c.getColumnIndexOrThrow("number")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        ed25519 = c.getString(c.getColumnIndexOrThrow("ed25519")),
        curve25519 = c.getString(c.getColumnIndexOrThrow("curve25519")),
        sealing = c.getString(c.getColumnIndexOrThrow("sealing")),
        signature = c.getString(c.getColumnIndexOrThrow("signature")),
        verified = c.getInt(c.getColumnIndexOrThrow("verified")) != 0,
        keyChanged = c.getInt(c.getColumnIndexOrThrow("key_changed")) != 0,
        addedTs = c.getLong(c.getColumnIndexOrThrow("added_ts"))
    )

    private fun readMessage(c: Cursor): ChatMessage? {
        val body = runCatching { KeystoreBox.decryptString(c.getBlob(c.getColumnIndexOrThrow("body_enc"))) }
            .getOrElse { return null }
        val errorIdx = c.getColumnIndexOrThrow("error")
        return ChatMessage(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            peer = c.getString(c.getColumnIndexOrThrow("peer")),
            direction = runCatching { Direction.valueOf(c.getString(c.getColumnIndexOrThrow("direction"))) }.getOrDefault(Direction.IN),
            body = body,
            ts = c.getLong(c.getColumnIndexOrThrow("ts")),
            status = c.getString(c.getColumnIndexOrThrow("status")),
            read = c.getInt(c.getColumnIndexOrThrow("read")) != 0,
            error = if (c.isNull(errorIdx)) null else c.getString(errorIdx)
        )
    }

    private companion object {
        const val DB_NAME = "comms_e2ee.db"
        const val DB_VERSION = 1
    }
}
