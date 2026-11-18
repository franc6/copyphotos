package app.copyphotos.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.copyphotos.core.CopiedFile
import app.copyphotos.core.CopyRecord
import app.copyphotos.core.Ledger

/**
 * The [Ledger] in SQLite, keyed by MediaStore ID: one row per item, plus one row per file it wrote.
 * Create one per app (AppGraph does).
 */
class CopyLedger(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "copy-ledger.db", null, 1), Ledger {

    private companion object {
        const val ITEMS = "copied"
        const val FILES = "copied_files"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $ITEMS (
                media_id INTEGER PRIMARY KEY,
                destination TEXT NOT NULL,
                copied_at INTEGER NOT NULL,
                source_size INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE $FILES (
                media_id INTEGER NOT NULL REFERENCES $ITEMS(media_id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                remote_path TEXT NOT NULL,
                size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                PRIMARY KEY (media_id, position)
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private inline fun <T> transaction(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return db.block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    override fun add(record: CopyRecord) = transaction {
        // Replacing the item row cascades to its old files.
        insertWithOnConflict(ITEMS, null, ContentValues().apply {
            put("media_id", record.mediaId)
            put("destination", record.destination)
            put("copied_at", record.copiedAt)
            put("source_size", record.sourceSize)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        record.files.forEachIndexed { i, f ->
            insertOrThrow(FILES, null, ContentValues().apply {
                put("media_id", record.mediaId)
                put("position", i)
                put("remote_path", f.remotePath)
                put("size", f.size)
                put("sha256", f.sha256Hex)
            })
        }
    }

    override fun copiedIds(): Set<Long> = readableDatabase.rawQuery("SELECT media_id FROM $ITEMS", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getLong(0)) }
    }

    override fun sourceSizes(): Map<Long, Long> =
        readableDatabase.rawQuery("SELECT media_id, source_size FROM $ITEMS WHERE source_size > 0", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getLong(1)) }
        }

    override fun record(id: Long): CopyRecord? {
        val args = arrayOf(id.toString())
        val db = readableDatabase
        val (destination, copiedAt, sourceSize) = db.rawQuery(
            "SELECT destination, copied_at, source_size FROM $ITEMS WHERE media_id = ?", args,
        ).use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getLong(1), c.getLong(2)) else return null }
        val files = db.rawQuery(
            "SELECT remote_path, size, sha256 FROM $FILES WHERE media_id = ? ORDER BY position", args,
        ).use { c -> buildList { while (c.moveToNext()) add(CopiedFile(c.getString(0), c.getLong(1), c.getString(2))) } }
        return CopyRecord(id, destination, files, copiedAt, sourceSize)
    }

    override fun remove(ids: Collection<Long>) = transaction {
        ids.forEach { delete(ITEMS, "media_id = ?", arrayOf(it.toString())) }
    }

    override fun prune(existing: Set<Long>): Int {
        val stale = copiedIds() - existing
        if (stale.isNotEmpty()) remove(stale)
        return stale.size
    }

    override fun clear() = transaction {
        delete(ITEMS, null, null)
        Unit
    }
}
