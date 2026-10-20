package com.rrrainielll.smsbridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Tracks which recording files have already been forwarded to Telegram.
 *
 * The primary key for de-duplication is a SHA-256 hash of the absolute file path and its
 * last-modified time. Using the mtime means that if a file is *replaced* on the same path we
 * will forward it again (desired), while the same file sitting in the same location is never
 * forwarded twice (including across app restarts).
 *
 * Forwarded paths are kept forever so they survive restarts; the table is only ever appended to,
 * never cleaned up. In practice, each row is ~100 B and voicemail counts rarely exceed hundreds.
 */
class RecordingStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE recordings (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                dedupe    TEXT    NOT NULL UNIQUE,
                path      TEXT    NOT NULL,
                queued_at INTEGER NOT NULL,
                status    INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_recordings_status ON recordings(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Returns true and inserts a row if [dedupe] has not been seen before.
     * Returns false (and does nothing) if this recording is already tracked.
     */
    fun insertIfNew(dedupe: String, path: String): Boolean {
        val values = ContentValues().apply {
            put("dedupe", dedupe)
            put("path", path)
            put("queued_at", System.currentTimeMillis())
            put("status", STATUS_PENDING)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "recordings", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        return rowId != -1L
    }

    /** Returns all recordings that have not yet been forwarded successfully. */
    fun pendingPaths(): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        readableDatabase.query(
            "recordings",
            arrayOf("id", "path"),
            "status = ?", arrayOf(STATUS_PENDING.toString()),
            null, null, "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                result += c.getLong(0) to c.getString(1)
            }
        }
        return result
    }

    fun markSent(id: Long) {
        val v = ContentValues().apply { put("status", STATUS_SENT) }
        writableDatabase.update("recordings", v, "id = ?", arrayOf(id.toString()))
    }

    fun markFailed(id: Long) {
        val v = ContentValues().apply { put("status", STATUS_FAILED) }
        writableDatabase.update("recordings", v, "id = ?", arrayOf(id.toString()))
    }

    /** Put permanently-failed recordings back in the queue for retry. */
    fun requeueFailed(): Int {
        val v = ContentValues().apply { put("status", STATUS_PENDING) }
        return writableDatabase.update(
            "recordings", v, "status = ?", arrayOf(STATUS_FAILED.toString())
        )
    }

    fun stats(): RecordingStats {
        return readableDatabase.rawQuery(
            """
            SELECT
              COALESCE(SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END), 0),
              COALESCE(SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END), 0)
            FROM recordings
            """.trimIndent(), null
        ).use { c ->
            c.moveToFirst()
            RecordingStats(sent = c.getInt(0), failed = c.getInt(1), queued = c.getInt(2))
        }
    }

    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_SENT    = 1
        const val STATUS_FAILED  = 2

        private const val DB_NAME    = "smsbridge_recordings.db"
        private const val DB_VERSION = 1

        @Volatile
        private var instance: RecordingStore? = null

        fun get(context: Context): RecordingStore =
            instance ?: synchronized(this) {
                instance ?: RecordingStore(context).also { instance = it }
            }
    }
}

data class RecordingStats(val sent: Int, val failed: Int, val queued: Int)
