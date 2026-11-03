package app.dailycare.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.dailycare.core.CareEvent
import app.dailycare.core.Motion
import app.dailycare.core.MotionTransition
import app.dailycare.core.StoredEvent
import app.dailycare.core.careEventFromJson
import app.dailycare.core.toJson
import app.dailycare.core.typeName

/**
 * On-device storage only. Three tables:
 * - events: the care log (JSON per event)
 * - step_minutes: steps per minute per source ("phone" now, a watch later)
 * - activity: activity-recognition transitions (fallback when there's no step counter)
 */
class CareDatabase(context: Context) : SQLiteOpenHelper(context, "care.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, type TEXT NOT NULL, json TEXT NOT NULL)")
        db.execSQL("CREATE INDEX events_at ON events(at)")
        db.execSQL("CREATE TABLE step_minutes (minute INTEGER NOT NULL, source TEXT NOT NULL, steps INTEGER NOT NULL, PRIMARY KEY(minute, source))")
        db.execSQL("CREATE TABLE activity (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, motion TEXT NOT NULL, entering INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX activity_at ON activity(at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private inline fun <T> transaction(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = db.block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun insertEvents(events: List<CareEvent>) {
        if (events.isEmpty()) return
        transaction {
            for (e in events) {
                val values = ContentValues().apply {
                    put("at", e.at)
                    put("type", e.typeName())
                    put("json", e.toJson())
                }
                insert("events", null, values)
            }
        }
    }

    fun events(from: Long, to: Long): List<StoredEvent> {
        val out = ArrayList<StoredEvent>()
        readableDatabase.rawQuery("SELECT id, json FROM events WHERE at >= $from AND at <= $to ORDER BY at, id", null).use { c ->
            while (c.moveToNext()) {
                careEventFromJson(c.getString(1))?.let { out += StoredEvent(c.getLong(0), it) }
            }
        }
        return out
    }

    fun event(id: Long): StoredEvent? =
        readableDatabase.rawQuery("SELECT id, json FROM events WHERE id = $id", null).use { c ->
            if (c.moveToFirst()) careEventFromJson(c.getString(1))?.let { StoredEvent(c.getLong(0), it) } else null
        }

    fun deleteEvent(id: Long): Boolean = writableDatabase.delete("events", "id = ?", arrayOf(id.toString())) > 0

    /** Adds to existing minute bins. (No UPSERT: Android 10 ships SQLite 3.22.) */
    fun addSteps(increments: Map<Long, Int>, source: String) {
        if (increments.isEmpty()) return
        transaction {
            val update = compileStatement("UPDATE step_minutes SET steps = steps + ? WHERE minute = ? AND source = ?")
            val insert = compileStatement("INSERT INTO step_minutes(minute, source, steps) VALUES (?, ?, ?)")
            for ((minute, steps) in increments) {
                update.clearBindings()
                update.bindLong(1, steps.toLong())
                update.bindLong(2, minute)
                update.bindString(3, source)
                if (update.executeUpdateDelete() == 0) {
                    insert.clearBindings()
                    insert.bindLong(1, minute)
                    insert.bindString(2, source)
                    insert.bindLong(3, steps.toLong())
                    insert.executeInsert()
                }
            }
        }
    }

    /** Steps per minute; when several sources counted the same minute, the highest wins. */
    fun stepBins(from: Long, to: Long): Map<Long, Int> {
        val out = HashMap<Long, Int>()
        readableDatabase.rawQuery(
            "SELECT minute, MAX(steps) FROM step_minutes WHERE minute >= $from AND minute <= $to GROUP BY minute",
            null,
        ).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getInt(1)
        }
        return out
    }

    fun addTransitions(list: List<MotionTransition>) {
        if (list.isEmpty()) return
        transaction {
            for (t in list) {
                val values = ContentValues().apply {
                    put("at", t.at)
                    put("motion", t.motion.name)
                    put("entering", if (t.entering) 1 else 0)
                }
                insert("activity", null, values)
            }
        }
    }

    fun transitions(from: Long, to: Long): List<MotionTransition> {
        val out = ArrayList<MotionTransition>()
        readableDatabase.rawQuery("SELECT at, motion, entering FROM activity WHERE at >= $from AND at <= $to ORDER BY at", null).use { c ->
            while (c.moveToNext()) {
                val motion = runCatching { Motion.valueOf(c.getString(1)) }.getOrDefault(Motion.OTHER)
                out += MotionTransition(c.getLong(0), motion, c.getInt(2) == 1)
            }
        }
        return out
    }

    /** Raw sensor rows are only needed for a few months; the care log is kept. */
    fun pruneSignals(before: Long) {
        writableDatabase.delete("step_minutes", "minute < ?", arrayOf(before.toString()))
        writableDatabase.delete("activity", "at < ?", arrayOf(before.toString()))
    }
}
