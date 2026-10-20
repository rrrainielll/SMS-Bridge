package com.rrrainielll.smsbridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** A queued SMS with its content decrypted (null fields mean the content could not be decrypted). */
class PendingMessage(
    val id: Long,
    val sender: String?,
    val body: String?,
    val receivedAt: Long,
    val partsSent: Int,
    /** True if a fallback SMS was already dispatched for this message (avoids duplicates on retry). */
    val smsFallbackSent: Boolean
)

data class Stats(val forwarded: Int, val failed: Int, val queued: Int)

/**
 * Local durable queue of SMS awaiting delivery.
 *
 * - Sender and body are stored encrypted (see [Crypto]) and are wiped as soon as the message has been
 *   delivered, leaving only an opaque hash used for duplicate detection and the counters.
 * - A UNIQUE hash column guarantees the same message is never queued (and therefore forwarded) twice.
 */
class MessageStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dedupe TEXT NOT NULL UNIQUE,
                sender TEXT NOT NULL,
                body TEXT NOT NULL,
                received_at INTEGER NOT NULL,
                status INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                parts_sent INTEGER NOT NULL DEFAULT 0,
                sms_fallback_sent INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_status ON messages(status, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2: add sms_fallback_sent column (default 0 = not yet sent).
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sms_fallback_sent INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Queues a message. Returns false if an identical message was already queued/forwarded. */
    fun insertIfNew(sender: String, body: String, receivedAt: Long): Boolean {
        val values = ContentValues().apply {
            put("dedupe", fingerprint(sender, body, receivedAt))
            put("sender", Crypto.encrypt(sender))
            put("body", Crypto.encrypt(body))
            put("received_at", receivedAt)
            put("status", STATUS_PENDING)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "messages", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        if (rowId != -1L) AppEvents.bump()
        return rowId != -1L
    }

    /** Oldest message still waiting for delivery, in arrival order. */
    fun nextPending(): PendingMessage? {
        readableDatabase.query(
            "messages",
            arrayOf("id", "sender", "body", "received_at", "parts_sent", "sms_fallback_sent"),
            "status = ?", arrayOf(STATUS_PENDING.toString()),
            null, null, "id ASC", "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return PendingMessage(
                id = c.getLong(0),
                sender = Crypto.decrypt(c.getString(1)),
                body = Crypto.decrypt(c.getString(2)),
                receivedAt = c.getLong(3),
                partsSent = c.getInt(4),
                smsFallbackSent = c.getInt(5) != 0
            )
        }
    }

    fun setPartsSent(id: Long, parts: Int) {
        val v = ContentValues().apply { put("parts_sent", parts) }
        writableDatabase.update("messages", v, "id = ?", arrayOf(id.toString()))
    }

    /** Marks delivered and erases the stored SMS content. */
    fun markSent(id: Long) {
        val v = ContentValues().apply {
            put("status", STATUS_SENT)
            put("sender", "")
            put("body", "")
        }
        writableDatabase.update("messages", v, "id = ?", arrayOf(id.toString()))
        AppEvents.bump()
    }

    /** Records a failed delivery attempt; the message stays queued for retry. */
    fun recordFailedAttempt(id: Long) {
        writableDatabase.execSQL("UPDATE messages SET attempts = attempts + 1 WHERE id = ?", arrayOf(id))
        AppEvents.bump()
    }

    /** Marks that a fallback SMS was dispatched so it is not re-sent on the next retry. */
    fun markSmsFallbackSent(id: Long) {
        val v = ContentValues().apply { put("sms_fallback_sent", 1) }
        writableDatabase.update("messages", v, "id = ?", arrayOf(id.toString()))
    }

    /** The message was rejected permanently; it is kept (encrypted) so the user can retry it. */
    fun markFailed(id: Long) {
        val v = ContentValues().apply {
            put("status", STATUS_FAILED)
        }
        writableDatabase.update("messages", v, "id = ?", arrayOf(id.toString()))
        writableDatabase.execSQL("UPDATE messages SET attempts = attempts + 1 WHERE id = ?", arrayOf(id))
        AppEvents.bump()
    }

    /** Puts permanently-failed messages back in the queue. */
    fun requeueFailed(): Int {
        val v = ContentValues().apply {
            put("status", STATUS_PENDING)
            put("attempts", 0)
            put("parts_sent", 0)
            put("sms_fallback_sent", 0) // Allow fallback to fire again on the next attempt.
        }
        val n = writableDatabase.update("messages", v, "status = ?", arrayOf(STATUS_FAILED.toString()))
        if (n > 0) AppEvents.bump()
        return n
    }

    fun hasPending(): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM messages WHERE status = 0 LIMIT 1", null
    ).use { it.moveToFirst() }

    fun stats(): Stats = readableDatabase.rawQuery(
        """
        SELECT
          COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0),
          COALESCE(SUM(CASE WHEN status = 2 OR (status = 0 AND attempts > 0) THEN 1 ELSE 0 END), 0),
          COALESCE(SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END), 0)
        FROM messages
        """.trimIndent(), null
    ).use { c ->
        c.moveToFirst()
        Stats(forwarded = c.getInt(0), failed = c.getInt(1), queued = c.getInt(2))
    }

    private fun fingerprint(sender: String, body: String, receivedAt: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sender\u0000$receivedAt\u0000$body".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_FAILED = 2
        private const val DB_NAME = "smsbridge_queue.db"
        private const val DB_VERSION = 2

        @Volatile
        private var instance: MessageStore? = null

        fun get(context: Context): MessageStore =
            instance ?: synchronized(this) {
                instance ?: MessageStore(context).also { instance = it }
            }
    }
}
