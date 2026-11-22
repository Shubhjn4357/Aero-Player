package com.emptysol.aeroplayer.data.cache

import android.content.Context
import com.emptysol.aeroplayer.data.database.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

object MediaCacheManager {
    private const val CACHE_FILE_NAME = "media_cache_snapshot.json"
    private const val PREFS_NAME = "media_cache_prefs"
    private const val KEY_LAST_SCAN_TIME = "last_scan_timestamp"

    private val memoryCache = AtomicReference<List<MediaEntity>?>(null)

    fun loadCachedMedia(context: Context): List<MediaEntity> {
        val cached = memoryCache.get()
        if (cached != null && cached.isNotEmpty()) {
            return cached
        }

        val file = File(context.filesDir, CACHE_FILE_NAME)
        if (!file.exists() || file.length() == 0L) {
            return emptyList()
        }

        try {
            val jsonString = file.readText()
            val jsonArray = JSONArray(jsonString)
            val list = ArrayList<MediaEntity>(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    MediaEntity(
                        uriString = obj.getString("uri"),
                        title = obj.getString("title"),
                        artist = obj.optString("artist", "Unknown"),
                        album = obj.optString("album", "Unknown"),
                        duration = obj.optLong("duration", 0L),
                        size = obj.optLong("size", 0L),
                        dateAdded = obj.optLong("dateAdded", 0L),
                        isVideo = obj.optBoolean("isVideo", true),
                        path = obj.optString("path", ""),
                        mimeType = if (obj.has("mime")) obj.getString("mime") else null,
                        subtitleUri = if (obj.has("subUri")) obj.getString("subUri") else null,
                        genre = obj.optString("genre", if (obj.optBoolean("isVideo", true)) "Video" else "Audio")
                    )
                )
            }
            memoryCache.set(list)
            return list
        } catch (e: Throwable) {
            e.printStackTrace()
            return emptyList()
        }
    }

    suspend fun saveCachedMedia(context: Context, items: List<MediaEntity>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        memoryCache.set(items)

        try {
            val jsonArray = JSONArray()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("uri", item.uriString)
                    put("title", item.title)
                    put("artist", item.artist ?: "")
                    put("album", item.album ?: "")
                    put("duration", item.duration)
                    put("size", item.size)
                    put("dateAdded", item.dateAdded)
                    put("isVideo", item.isVideo)
                    put("path", item.path)
                    if (item.mimeType != null) put("mime", item.mimeType)
                    if (item.subtitleUri != null) put("subUri", item.subtitleUri)
                    put("genre", item.genre ?: "")
                }
                jsonArray.put(obj)
            }

            val targetFile = File(context.filesDir, CACHE_FILE_NAME)
            val tempFile = File(context.filesDir, "$CACHE_FILE_NAME.tmp")
            tempFile.writeText(jsonArray.toString())
            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun getLastScanTimestamp(context: Context): Long {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getLong(KEY_LAST_SCAN_TIME, 0L)
    }

    fun setLastScanTimestamp(context: Context, timestamp: Long) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putLong(KEY_LAST_SCAN_TIME, timestamp).apply()
    }

    fun shouldSkipAutoScan(context: Context): Boolean {
        val cached = memoryCache.get() ?: loadCachedMedia(context)
        if (cached.isEmpty()) return false
        val lastScan = getLastScanTimestamp(context)
        val elapsed = System.currentTimeMillis() - lastScan
        return elapsed < 15 * 60 * 1000L
    }
}
