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
        createVersion3(db)
        createVersion4(db)
        createVersion5(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 was the first schema for the end-to-end module. The Twilio-era
        // database (comms.db) is deleted by CommsRepository.init on first run.
        if (oldVersion < 2) createVersion2(db)
        if (oldVersion < 3) createVersion3(db)
        if (oldVersion < 4) createVersion4(db)
        if (oldVersion < 5) createVersion5(db)
    }

    /** Names of the columns [table] has, so a migration can add one only when it is missing. */
    private fun columnsOf(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.toSet()
        }

    /**
     * Organisation groups: the groups this identity is in, their rosters, the
     * threats members shared, and two columns on messages so a group
     * conversation (peer = group id) knows its group and who in it sent what.
     */
    private fun createVersion5(db: SQLiteDatabase) {
        val columns = columnsOf(db, "messages")
        if ("gid" !in columns) db.execSQL("ALTER TABLE messages ADD COLUMN gid TEXT")
        if ("sender" !in columns) db.execSQL("ALTER TABLE messages ADD COLUMN sender TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS messages_gid_ts ON messages (gid, ts)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS groups (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                created_by TEXT NOT NULL,
                created_ts INTEGER NOT NULL,
                my_role TEXT NOT NULL DEFAULT 'MEMBER'
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS group_members (
                gid TEXT NOT NULL,
                number TEXT NOT NULL,
                name TEXT NOT NULL DEFAULT '',
                role TEXT NOT NULL DEFAULT 'MEMBER',
                ed25519 TEXT NOT NULL DEFAULT '',
                added_by TEXT NOT NULL DEFAULT '',
                added_ts INTEGER NOT NULL,
                PRIMARY KEY (gid, number)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS shared_threats (
                id TEXT PRIMARY KEY,
                gid TEXT NOT NULL,
                from_number TEXT NOT NULL,
                from_name TEXT NOT NULL DEFAULT '',
                ts INTEGER NOT NULL,
                kind TEXT NOT NULL,
                severity TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                detail TEXT NOT NULL DEFAULT '',
                expires_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS shared_threats_gid_ts ON shared_threats (gid, ts)")
    }

    /**
     * Photos and videos: a message kind (text or media) and the chunks of an
     * inbound file while the rest are still on their way. Chunks are encrypted
     * like bodies; the assembled file goes to [CommsMedia].
     */
    private fun createVersion4(db: SQLiteDatabase) {
        val hasKind = db.rawQuery("PRAGMA table_info(messages)", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(c.getColumnIndexOrThrow("name")) else null }.any { it == "kind" }
        }
        if (!hasKind) db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT '$KIND_TEXT'")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS media_chunks (
                id TEXT NOT NULL,
                idx INTEGER NOT NULL,
                ts INTEGER NOT NULL,
                data_enc BLOB NOT NULL,
                PRIMARY KEY (id, idx)
            )"""
        )
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

    /** AegisCoin: the transfers this phone made and received, one row each. */
    private fun createVersion3(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS transactions (
                id TEXT PRIMARY KEY,
                peer TEXT NOT NULL,
                direction TEXT NOT NULL,
                amount INTEGER NOT NULL,
                ts INTEGER NOT NULL,
                note TEXT NOT NULL,
                txid TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS transactions_peer_ts ON transactions (peer, ts)")
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

    /** Deletes everything about [number]; returns the ids of its media messages so their files can go too. */
    fun deleteContact(number: String): List<String> {
        val media = mediaIds(number)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("receipts", "peer = ?", arrayOf(number))
            for (id in media) db.delete("media_chunks", "id = ?", arrayOf(id))
            db.delete("messages", "peer = ?", arrayOf(number))
            db.delete("transactions", "peer = ?", arrayOf(number))
            db.delete("contacts", "number = ?", arrayOf(number))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return media
    }

    // ── Messages ─────────────────────────────────────────────────────────

    /**
     * Stores a message; a second copy of the same id is ignored. With [gid] it is
     * a group message: its row belongs to the group conversation (peer is the
     * group id, whatever [m.peer] says), and [ChatMessage.sender] names the member.
     */
    fun insertMessage(m: ChatMessage, gid: String? = null) {
        val values = ContentValues().apply {
            put("id", m.id)
            put("peer", gid ?: m.peer)
            put("direction", m.direction.name)
            put("body_enc", KeystoreBox.encryptString(m.body))
            put("ts", m.ts)
            put("status", m.status)
            put("read", if (m.read) 1 else 0)
            put("error", m.error)
            put("kind", m.kind)
            put("gid", gid)
            put("sender", m.sender)
        }
        writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Stores a message in group [gid]'s conversation; see [insertMessage]. */
    fun insertGroupMessage(gid: String, m: ChatMessage) = insertMessage(m, gid)

    fun hasMessage(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM messages WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    fun message(id: String): ChatMessage? =
        readableDatabase.query("messages", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) readMessage(c) else null
        }

    /** Ids of the media messages with [peer] (or everyone, when null), whose files live in [CommsMedia]. */
    fun mediaIds(peer: String? = null): List<String> =
        readableDatabase.rawQuery(
            "SELECT id FROM messages WHERE kind = ?" + (if (peer != null) " AND peer = ?" else ""),
            if (peer != null) arrayOf(KIND_MEDIA, peer) else arrayOf(KIND_MEDIA)
        ).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

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
     * Marks a message the relay took as "sent", unless the recipient's receipt
     * got here first and already moved it further. A photo takes many envelopes
     * and seconds to go, so its "delivered" can easily arrive before the last
     * chunk is confirmed; it must not be pulled back to "sent".
     */
    /** A message of ours to [peer] that the relay will never take (too large, say): failed for good, with a RETRY in the bubble. */
    fun markFailed(peer: String, id: String, error: String? = null) {
        writableDatabase.execSQL(
            "UPDATE messages SET status = 'failed', error = ? WHERE peer = ? AND id = ? AND direction = 'OUT'",
            arrayOf(error, peer, id)
        )
    }

    /**
     * Inbound photos and videos announced before [olderThan] whose chunks never
     * all came are marked failed, so the bubble stops saying "receiving"; their
     * chunks go too. Returns how many.
     */
    fun expireStaleMedia(olderThan: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = db.rawQuery(
                "SELECT id FROM messages WHERE kind = ? AND direction = 'IN' AND status = ? AND ts < ?",
                arrayOf(KIND_MEDIA, STATUS_RECEIVING, olderThan.toString())
            ).use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) out += c.getString(0)
                out
            }
            for (id in ids) {
                db.execSQL("UPDATE messages SET status = 'failed', error = ? WHERE id = ?", arrayOf("The file never arrived in full", id))
                db.delete("media_chunks", "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
            return ids.size
        } finally {
            db.endTransaction()
        }
    }

    fun markSentIfQueued(id: String) {
        val values = ContentValues().apply { put("status", "sent"); putNull("error") }
        writableDatabase.update("messages", values, "id = ? AND status = 'queued'", arrayOf(id))
    }

    /**
     * Advances the status of our messages to [peer], never backwards (a late
     * "delivered" after "read"). Only messages sent to that contact can be
     * receipted by them; nothing is decrypted.
     *
     * Messages whose status says what they are (a voicemail, a payment) are
     * left alone: an unknown status used to rank below everything, so the first
     * receipt for a voicemail overwrote "voicemail" with "delivered", and the
     * sender's bubble turned into the recording's base64 as text.
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
                val currentRank = rank[current] ?: continue
                if (currentRank < target) {
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
            // A photo still arriving is neither shown nor receipted yet; it is read once it is whole.
            val ids = db.rawQuery(
                "SELECT id FROM messages WHERE peer = ? AND direction = 'IN' AND read = 0 AND status != ? AND status != ?",
                arrayOf(peer, STATUS_CALL, STATUS_RECEIVING)
            ).use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) out += c.getString(0)
                out
            }
            for (id in ids) db.execSQL("UPDATE messages SET read = 1 WHERE id = ?", arrayOf(id))
            // Missed-call entries are read too, but are not messages to receipt.
            db.execSQL("UPDATE messages SET read = 1 WHERE peer = ? AND direction = 'IN' AND read = 0 AND status != ?", arrayOf(peer, STATUS_RECEIVING))
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

    // ── Organisation groups ──────────────────────────────────────────────

    /** Inserts or updates a group (name and role); its members are kept as they are. */
    fun insertGroup(group: Group) {
        val values = ContentValues().apply {
            put("id", group.id)
            put("name", group.name)
            put("created_by", group.createdBy)
            put("created_ts", group.createdTs)
            put("my_role", group.myRole.name)
        }
        writableDatabase.insertWithOnConflict("groups", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun groups(): List<Group> =
        readableDatabase.query("groups", null, null, null, null, null, "name COLLATE NOCASE ASC").use { c ->
            val out = ArrayList<Group>()
            while (c.moveToNext()) out += readGroup(c)
            out
        }

    fun group(id: String): Group? =
        readableDatabase.query("groups", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) readGroup(c) else null
        }

    /** Deletes the group, its roster, its conversation and the threats shared in it; returns the ids of its media messages. */
    fun deleteGroup(id: String): List<String> {
        val media = mediaIds(id)
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (m in media) db.delete("media_chunks", "id = ?", arrayOf(m))
            db.delete("messages", "gid = ? OR peer = ?", arrayOf(id, id))
            db.delete("shared_threats", "gid = ?", arrayOf(id))
            db.delete("group_members", "gid = ?", arrayOf(id))
            db.delete("groups", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return media
    }

    fun setGroupRole(gid: String, myRole: OrgRole) {
        writableDatabase.update("groups", ContentValues().apply { put("my_role", myRole.name) }, "id = ?", arrayOf(gid))
    }

    /** Inserts or updates a member; the same number in the same group is one row. */
    fun insertGroupMember(member: GroupMember) {
        val values = ContentValues().apply {
            put("gid", member.gid)
            put("number", member.number)
            put("name", member.name)
            put("role", member.role.name)
            put("ed25519", member.ed25519)
            put("added_by", member.addedBy)
            put("added_ts", member.addedTs)
        }
        writableDatabase.insertWithOnConflict("group_members", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun groupMembers(gid: String): List<GroupMember> =
        readableDatabase.query("group_members", null, "gid = ?", arrayOf(gid), null, null, "role ASC, name COLLATE NOCASE ASC").use { c ->
            val out = ArrayList<GroupMember>()
            while (c.moveToNext()) out += readGroupMember(c)
            out
        }

    fun groupMember(gid: String, number: String): GroupMember? =
        readableDatabase.query("group_members", null, "gid = ? AND number = ?", arrayOf(gid, number), null, null, null).use { c ->
            if (c.moveToFirst()) readGroupMember(c) else null
        }

    fun removeGroupMember(gid: String, number: String) {
        writableDatabase.delete("group_members", "gid = ? AND number = ?", arrayOf(gid, number))
    }

    /** Records a shared threat; a second copy of the same id (a re-sent envelope) is ignored. */
    fun insertSharedThreat(threat: SharedThreat) {
        val values = ContentValues().apply {
            put("id", threat.id)
            put("gid", threat.gid)
            put("from_number", threat.fromNumber)
            put("from_name", threat.fromName)
            put("ts", threat.ts)
            put("kind", threat.kind)
            put("severity", threat.severity)
            put("lat", threat.lat)
            put("lon", threat.lon)
            put("title", threat.title)
            put("detail", threat.detail)
            put("expires_at", threat.expiresAt)
        }
        writableDatabase.insertWithOnConflict("shared_threats", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun hasSharedThreat(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM shared_threats WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    /** Threats shared in [gid] (or in every group, when null), newest first. */
    fun sharedThreats(gid: String? = null): List<SharedThreat> =
        readableDatabase.query(
            "shared_threats", null, if (gid != null) "gid = ?" else null, if (gid != null) arrayOf(gid) else null,
            null, null, "ts DESC"
        ).use { c ->
            val out = ArrayList<SharedThreat>()
            while (c.moveToNext()) out += readSharedThreat(c)
            out
        }

    /** Forgets threats whose time is up; returns how many. */
    fun expireSharedThreats(now: Long): Int =
        writableDatabase.compileStatement("DELETE FROM shared_threats WHERE expires_at < ?").use { s ->
            s.bindLong(1, now)
            s.executeUpdateDelete()
        }

    /** The newest [limit] messages in group [gid]'s conversation, newest first. */
    fun groupMessages(gid: String, limit: Int = 100): List<ChatMessage> =
        readableDatabase.query("messages", null, "gid = ?", arrayOf(gid), null, null, "ts DESC", limit.toString()).use { c ->
            val out = ArrayList<ChatMessage>()
            while (c.moveToNext()) readMessage(c)?.let { out += it }
            out
        }

    /** Marks a group conversation read; group messages are not receipted, so nothing is returned. */
    fun markGroupRead(gid: String) {
        writableDatabase.execSQL("UPDATE messages SET read = 1 WHERE gid = ? AND direction = 'IN' AND read = 0", arrayOf(gid))
    }

    fun groupUnread(gid: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE gid = ? AND direction = 'IN' AND read = 0", arrayOf(gid)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** The newest unread inbound messages in group [gid], oldest first, for the notification. */
    fun unreadGroupInbound(gid: String, limit: Int): List<ChatMessage> =
        readableDatabase.query(
            "messages", null, "gid = ? AND direction = 'IN' AND read = 0", arrayOf(gid), null, null, "ts DESC", limit.toString()
        ).use { c ->
            val out = ArrayList<ChatMessage>()
            while (c.moveToNext()) readMessage(c)?.let { out += it }
            out.reversed()
        }

    private fun readGroup(c: Cursor) = Group(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        createdBy = c.getString(c.getColumnIndexOrThrow("created_by")),
        createdTs = c.getLong(c.getColumnIndexOrThrow("created_ts")),
        myRole = runCatching { OrgRole.valueOf(c.getString(c.getColumnIndexOrThrow("my_role"))) }.getOrDefault(OrgRole.MEMBER)
    )

    private fun readGroupMember(c: Cursor) = GroupMember(
        gid = c.getString(c.getColumnIndexOrThrow("gid")),
        number = c.getString(c.getColumnIndexOrThrow("number")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        role = runCatching { OrgRole.valueOf(c.getString(c.getColumnIndexOrThrow("role"))) }.getOrDefault(OrgRole.MEMBER),
        ed25519 = c.getString(c.getColumnIndexOrThrow("ed25519")),
        addedBy = c.getString(c.getColumnIndexOrThrow("added_by")),
        addedTs = c.getLong(c.getColumnIndexOrThrow("added_ts"))
    )

    private fun readSharedThreat(c: Cursor) = SharedThreat(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        gid = c.getString(c.getColumnIndexOrThrow("gid")),
        fromNumber = c.getString(c.getColumnIndexOrThrow("from_number")),
        fromName = c.getString(c.getColumnIndexOrThrow("from_name")),
        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        severity = c.getString(c.getColumnIndexOrThrow("severity")),
        lat = c.getDouble(c.getColumnIndexOrThrow("lat")),
        lon = c.getDouble(c.getColumnIndexOrThrow("lon")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        detail = c.getString(c.getColumnIndexOrThrow("detail")),
        expiresAt = c.getLong(c.getColumnIndexOrThrow("expires_at"))
    )

    // ── Media chunks on their way in ─────────────────────────────────────

    /** Keeps chunk [index] of media [id]; a second copy of the same chunk is ignored. */
    fun saveChunk(id: String, index: Int, data: ByteArray, ts: Long) {
        val values = ContentValues().apply {
            put("id", id)
            put("idx", index)
            put("ts", ts)
            put("data_enc", KeystoreBox.encrypt(data))
        }
        writableDatabase.insertWithOnConflict("media_chunks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun chunkCount(id: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM media_chunks WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Every chunk of [id] in order, or null when one is missing or unreadable. */
    fun assembleChunks(id: String, expected: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var next = 0
        readableDatabase.query("media_chunks", arrayOf("idx", "data_enc"), "id = ?", arrayOf(id), null, null, "idx ASC").use { c ->
            while (c.moveToNext()) {
                if (c.getInt(0) != next) return null
                val plain = runCatching { KeystoreBox.decrypt(c.getBlob(1)) }.getOrNull() ?: return null
                out.write(plain)
                next++
            }
        }
        return if (next == expected) out.toByteArray() else null
    }

    fun deleteChunks(id: String) {
        writableDatabase.delete("media_chunks", "id = ?", arrayOf(id))
    }

    /** Chunks of files whose header, or whose remaining chunks, never came. */
    fun purgeChunks(olderThan: Long) {
        writableDatabase.execSQL("DELETE FROM media_chunks WHERE ts < ?", arrayOf(olderThan))
    }

    /** Ids of chunk sets that have no message row yet (their header has not arrived). */
    fun orphanChunkIds(): List<String> =
        readableDatabase.rawQuery("SELECT DISTINCT id FROM media_chunks WHERE id NOT IN (SELECT id FROM messages)", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    // ── AegisCoin ────────────────────────────────────────────────────────

    /** Records a transfer; a second copy of the same id (a re-sent payment notice) is ignored. */
    fun insertTransaction(tx: CoinTx) {
        val values = ContentValues().apply {
            put("id", tx.id)
            put("peer", tx.peer)
            put("direction", tx.direction.name)
            put("amount", tx.amount)
            put("ts", tx.ts)
            put("note", tx.note)
            put("txid", tx.txid)
        }
        writableDatabase.insertWithOnConflict("transactions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun hasTransaction(id: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM transactions WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    /** Transfers with [peer], newest first. */
    fun transactions(peer: String, limit: Int = 50): List<CoinTx> =
        readableDatabase.query("transactions", null, "peer = ?", arrayOf(peer), null, null, "ts DESC", limit.toString()).use { c ->
            val out = ArrayList<CoinTx>()
            while (c.moveToNext()) out += readTransaction(c)
            out
        }

    /** Every transfer, newest first. */
    fun allTransactions(limit: Int = 100): List<CoinTx> =
        readableDatabase.query("transactions", null, null, null, null, null, "ts DESC", limit.toString()).use { c ->
            val out = ArrayList<CoinTx>()
            while (c.moveToNext()) out += readTransaction(c)
            out
        }

    private fun readTransaction(c: Cursor) = CoinTx(
        id = c.getString(c.getColumnIndexOrThrow("id")),
        peer = c.getString(c.getColumnIndexOrThrow("peer")),
        direction = runCatching { Direction.valueOf(c.getString(c.getColumnIndexOrThrow("direction"))) }.getOrDefault(Direction.IN),
        amount = c.getLong(c.getColumnIndexOrThrow("amount")),
        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
        note = c.getString(c.getColumnIndexOrThrow("note")),
        txid = c.getString(c.getColumnIndexOrThrow("txid"))
    )

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
            db.delete("transactions", null, null)
            db.delete("media_chunks", null, null)
            db.delete("groups", null, null)
            db.delete("group_members", null, null)
            db.delete("shared_threats", null, null)
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
        val kindIdx = c.getColumnIndex("kind")
        val senderIdx = c.getColumnIndex("sender")
        return ChatMessage(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            peer = c.getString(c.getColumnIndexOrThrow("peer")),
            direction = runCatching { Direction.valueOf(c.getString(c.getColumnIndexOrThrow("direction"))) }.getOrDefault(Direction.IN),
            body = body,
            ts = c.getLong(c.getColumnIndexOrThrow("ts")),
            status = c.getString(c.getColumnIndexOrThrow("status")),
            read = c.getInt(c.getColumnIndexOrThrow("read")) != 0,
            error = if (c.isNull(errorIdx)) null else c.getString(errorIdx),
            kind = if (kindIdx >= 0 && !c.isNull(kindIdx)) c.getString(kindIdx) else KIND_TEXT,
            sender = if (senderIdx >= 0 && !c.isNull(senderIdx)) c.getString(senderIdx) else null
        )
    }

    private companion object {
        const val DB_NAME = "comms_e2ee.db"
        const val DB_VERSION = 5
    }
}
