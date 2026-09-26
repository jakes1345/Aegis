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
        // Envelope ids already opened, so a redelivery is never processed twice.
        db.execSQL(
            """CREATE TABLE seen_envelopes (
                id TEXT PRIMARY KEY,
                ts INTEGER NOT NULL
            )"""
        )
        // Every decrypted payload is staged here, in the same transaction that
        // marks its envelope seen, before anything that can fail acts on it.
        // The ratchet has moved on, so this copy is the only one; a row stays
        // until the payload has been processed (or its sender refused).
        db.execSQL(
            """CREATE TABLE pending_inbound (
                id TEXT PRIMARY KEY,
                ts INTEGER NOT NULL,
                sender TEXT NOT NULL,
                payload_enc BLOB NOT NULL
            )"""
        )
        createVersion2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 was the first schema for the end-to-end module. The Twilio-era
        // database (comms.db) is deleted by CommsRepository.init on first run.
        if (oldVersion < 2) createVersion2(db)
    }

    /** Receipts owed to contacts (sent, and retried, outside the repository lock) and an index for purging. */
    private fun createVersion2(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS receipts (
                peer TEXT NOT NULL,
                id TEXT NOT NULL,
                status TEXT NOT NULL,
                PRIMARY KEY (peer, id)
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS seen_envelopes_ts ON seen_envelopes (ts)")
    }

    // ── Contacts ─────────────────────────────────────────────────────────

    /**
     * Inserts or updates the contact with this number. A different contact that
     * already holds the same Curve25519 key makes this throw
     * [android.database.sqlite.SQLiteConstraintException] rather than silently
     * deleting that contact, which is what SQLite's REPLACE would do.
     */
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
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updated = db.update("contacts", values, "number = ?", arrayOf(c.number))
            if (updated == 0) db.insertOrThrow("contacts", null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
            db.delete("receipts", "peer = ?", arrayOf(number))
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

    /** Whether the inbound message [id] has been read; null when there is no such message. */
    fun isRead(id: String): Boolean? =
        readableDatabase.rawQuery("SELECT read FROM messages WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getInt(0) != 0 else null
        }

    /** A message's status and error, without decrypting its body. */
    fun statusOf(id: String): Pair<String, String?>? =
        readableDatabase.rawQuery("SELECT status, error FROM messages WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getString(0) to (if (c.isNull(1)) null else c.getString(1)) else null
        }

    fun setStatus(id: String, status: String, error: String? = null) {
        val values = ContentValues().apply { put("status", status); put("error", error) }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    /**
     * Advances the status of our messages to [peer], never backwards (a late
     * "delivered" after "read"). Only messages sent to that contact can be
     * receipted by them; nothing is decrypted.
     */
    fun advanceStatus(peer: String, ids: List<String>, status: String) {
        if (ids.isEmpty()) return
        val rank = mapOf("queued" to 0, "sent" to 1, "delivered" to 2, "read" to 3)
        val target = rank[status] ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                val current = db.rawQuery("SELECT status FROM messages WHERE id = ? AND peer = ? AND direction = 'OUT'", arrayOf(id, peer)).use { c ->
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

    /**
     * Puts messages to [peer] that the relay took but [peer] never confirmed
     * back in the send queue; returns how many. Used when [peer] reports it
     * could not read something from this phone: whatever that was is gone from
     * the relay, and resending is safe because the receiver drops a message
     * id it already has.
     */
    fun requeueUndelivered(peer: String, sinceTs: Long): Int {
        val values = ContentValues().apply { put("status", "queued"); putNull("error") }
        return writableDatabase.update(
            "messages", values,
            "peer = ? AND direction = 'OUT' AND status = 'sent' AND ts > ?", arrayOf(peer, sinceTs.toString())
        )
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

    /**
     * Marks a conversation read and returns the ids that were unread, for read
     * receipts. Only those ids are updated, so a message arriving in between is
     * neither marked read unseen nor left out of the receipt.
     */
    fun markRead(peer: String): List<String> {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = db.rawQuery("SELECT id FROM messages WHERE peer = ? AND direction = 'IN' AND read = 0 AND status != ?", arrayOf(peer, STATUS_CALL)).use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) out += c.getString(0)
                out
            }
            for (id in ids) db.execSQL("UPDATE messages SET read = 1 WHERE id = ?", arrayOf(id))
            // Missed-call entries are read too, but are not messages to receipt.
            db.execSQL("UPDATE messages SET read = 1 WHERE peer = ? AND direction = 'IN' AND read = 0", arrayOf(peer))
            db.setTransactionSuccessful()
            return ids
        } finally {
            db.endTransaction()
        }
    }

    /** The newest unread inbound messages from [peer], for the notification; only these are decrypted. */
    fun unreadInbound(peer: String, limit: Int): List<ChatMessage> =
        readableDatabase.query(
            "messages", null, "peer = ? AND direction = 'IN' AND read = 0", arrayOf(peer), null, null, "ts DESC", limit.toString()
        ).use { c ->
            val out = ArrayList<ChatMessage>()
            while (c.moveToNext()) readMessage(c)?.let { out += it }
            out.reversed()
        }

    fun unreadCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE direction = 'IN' AND read = 0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    // ── Envelope bookkeeping ─────────────────────────────────────────────

    fun isEnvelopeSeen(key: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM seen_envelopes WHERE id = ?", arrayOf(key)).use { it.moveToFirst() }

    /** Records an envelope; false when it was already processed. [key] is a digest of its content. */
    fun markEnvelopeSeen(key: String, ts: Long): Boolean {
        val values = ContentValues().apply { put("id", key); put("ts", ts) }
        val row = writableDatabase.insertWithOnConflict("seen_envelopes", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (row != -1L) purgeSeen()
        return row != -1L
    }

    /**
     * Marks the envelope [key] seen and stages its decrypted [payload] in one
     * transaction, so a crash or an error afterwards can never leave it seen
     * but unprocessed. False when it was already seen.
     */
    fun markSeenAndStage(key: String, ts: Long, senderCurve25519: String, payload: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val seen = ContentValues().apply { put("id", key); put("ts", ts) }
            if (db.insertWithOnConflict("seen_envelopes", null, seen, SQLiteDatabase.CONFLICT_IGNORE) == -1L) return false
            val staged = ContentValues().apply {
                put("id", key)
                put("ts", ts)
                put("sender", senderCurve25519)
                put("payload_enc", KeystoreBox.encryptString(payload))
            }
            db.insertWithOnConflict("pending_inbound", null, staged, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        purgeSeen()
        return true
    }

    @Volatile
    private var lastSeenPurge = 0L

    /** Envelopes older than the relay keeps them (30 days) can never come back; forget them hourly. */
    private fun purgeSeen() {
        val now = System.currentTimeMillis()
        if (now - lastSeenPurge < 3_600_000L) return
        lastSeenPurge = now
        writableDatabase.execSQL("DELETE FROM seen_envelopes WHERE ts < ?", arrayOf(now - 45L * 24 * 3600_000L))
    }

    // ── Receipts owed ────────────────────────────────────────────────────

    /** Records that [peer] is owed a receipt for [ids]; "read" replaces "delivered", never the reverse. */
    fun queueReceipt(peer: String, ids: List<String>, status: String) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.execSQL(
                    "INSERT INTO receipts (peer, id, status) VALUES (?, ?, ?) " +
                        "ON CONFLICT(peer, id) DO UPDATE SET status = excluded.status WHERE receipts.status != 'read'",
                    arrayOf(peer, id, status)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun hasPendingReceipts(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM receipts LIMIT 1", null).use { it.moveToFirst() }

    /** Receipts owed, by contact and then status. */
    fun pendingReceipts(): Map<String, Map<String, List<String>>> {
        val out = LinkedHashMap<String, MutableMap<String, MutableList<String>>>()
        readableDatabase.rawQuery("SELECT peer, id, status FROM receipts", null).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getString(0)) { LinkedHashMap() }.getOrPut(c.getString(2)) { ArrayList() } += c.getString(1)
            }
        }
        return out
    }

    /** Forgets receipts that went out (or can never go); a receipt upgraded to "read" meanwhile stays. */
    fun clearReceipts(peer: String, ids: List<String>, status: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete("receipts", "peer = ? AND id = ? AND status = ?", arrayOf(peer, id, status))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearReceiptsFor(peer: String) {
        writableDatabase.delete("receipts", "peer = ?", arrayOf(peer))
    }

    // ── Inbound payloads awaiting sender confirmation ────────────────────

    data class PendingInbound(val id: String, val ts: Long, val senderCurve25519: String, val payload: String)

    fun savePending(id: String, ts: Long, senderCurve25519: String, payload: String) {
        val values = ContentValues().apply {
            put("id", id)
            put("ts", ts)
            put("sender", senderCurve25519)
            put("payload_enc", KeystoreBox.encryptString(payload))
        }
        writableDatabase.insertWithOnConflict("pending_inbound", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun pending(): List<PendingInbound> =
        readableDatabase.query("pending_inbound", null, null, null, null, null, "ts ASC").use { c ->
            val out = ArrayList<PendingInbound>()
            while (c.moveToNext()) {
                val payload = runCatching { KeystoreBox.decryptString(c.getBlob(c.getColumnIndexOrThrow("payload_enc"))) }
                    .getOrNull() ?: continue
                out += PendingInbound(
                    c.getString(c.getColumnIndexOrThrow("id")),
                    c.getLong(c.getColumnIndexOrThrow("ts")),
                    c.getString(c.getColumnIndexOrThrow("sender")),
                    payload
                )
            }
            out
        }

    fun hasPending(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM pending_inbound LIMIT 1", null).use { it.moveToFirst() }

    fun deletePending(id: String) {
        writableDatabase.delete("pending_inbound", "id = ?", arrayOf(id))
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("messages", null, null)
            db.delete("contacts", null, null)
            db.delete("seen_envelopes", null, null)
            db.delete("pending_inbound", null, null)
            db.delete("receipts", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
        const val DB_VERSION = 2
    }
}
