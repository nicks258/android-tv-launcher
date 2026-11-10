package dev.plume.tmdb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache

class TmdbCache(context: Context) {

    private val dbHelper = DatabaseHelper(context.applicationContext)
    private val dominantColorCache = LruCache<Int, Int>(200)

    companion object {
        private const val DB_NAME = "plume_tmdb_cache.db"
        private const val DB_VERSION = 1

        const val EXPIRY_CONFIG_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        const val EXPIRY_GENRES_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        const val EXPIRY_ROW_MS = 12L * 60 * 60 * 1000 // 12 hours
        const val EXPIRY_DETAILS_MS = 24L * 60 * 60 * 1000 // 24 hours
    }

    private class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS tmdb_cache (" +
                        "cache_key TEXT PRIMARY KEY, " +
                        "json_data TEXT, " +
                        "updated_at INTEGER)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS tmdb_cache")
            onCreate(db)
        }
    }

    @Synchronized
    fun get(key: String, maxAgeMs: Long): String? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT json_data, updated_at FROM tmdb_cache WHERE cache_key = ?",
            arrayOf(key)
        )
        cursor.use {
            if (it.moveToFirst()) {
                val json = it.getString(0)
                val updatedAt = it.getLong(1)
                val age = System.currentTimeMillis() - updatedAt
                if (age <= maxAgeMs) {
                    return json
                }
            }
        }
        return null
    }

    @Synchronized
    fun put(key: String, jsonData: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("cache_key", key)
            put("json_data", jsonData)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("tmdb_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getDominantColor(id: Int): Int? = dominantColorCache.get(id)

    fun putDominantColor(id: Int, color: Int) {
        dominantColorCache.put(id, color)
    }

    fun clear() {
        val db = dbHelper.writableDatabase
        db.delete("tmdb_cache", null, null)
        dominantColorCache.evictAll()
    }
}
