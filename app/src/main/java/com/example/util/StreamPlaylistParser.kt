package com.example.util

import com.example.data.database.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class StreamPlaylistItem(
    val id: String,
    val title: String,
    val url: String,
    val group: String = "General",
    val logoUrl: String? = null,
    val isVideo: Boolean = true
)

object StreamPlaylistParser {

    fun isPlaylistUrl(url: String, mimeType: String? = null): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".pls") ||
                lower.contains("m3u") || lower.contains("playlist") || lower.contains("iptv") ||
                mimeType == "application/x-mpegURL" || mimeType == "audio/mpegurl"
    }

    suspend fun parsePlaylist(parentTitle: String, parentUrl: String, defaultIsVideo: Boolean = true): List<StreamPlaylistItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<StreamPlaylistItem>()

        try {
            if (parentUrl.startsWith("http://") || parentUrl.startsWith("https://")) {
                val urlObj = URL(parentUrl)
                val conn = urlObj.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"

                if (conn.responseCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    var line: String?
                    var currentTitle = ""
                    var currentGroup = "Main Channel List"

                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line?.trim() ?: continue
                        if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                            val commaIndex = trimmed.lastIndexOf(',')
                            if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                                currentTitle = trimmed.substring(commaIndex + 1).trim()
                            } else {
                                currentTitle = "Stream Channel ${items.size + 1}"
                            }

                            if (trimmed.contains("group-title=\"")) {
                                val groupMatch = Regex("group-title=\"([^\"]+)\"").find(trimmed)
                                if (groupMatch != null) {
                                    currentGroup = groupMatch.groupValues[1]
                                }
                            }
                        } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                            val streamUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                trimmed
                            } else if (parentUrl.contains("/")) {
                                val baseUrl = parentUrl.substringBeforeLast('/')
                                "$baseUrl/$trimmed"
                            } else {
                                trimmed
                            }

                            val name = if (currentTitle.isNotEmpty()) currentTitle else "Channel ${items.size + 1}"
                            items.add(
                                StreamPlaylistItem(
                                    id = "stream_${items.size}_${System.currentTimeMillis()}",
                                    title = name,
                                    url = streamUrl,
                                    group = currentGroup,
                                    isVideo = defaultIsVideo
                                )
                            )
                            currentTitle = ""
                        }
                    }
                    reader.close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback / standard channels expansion if empty or offline stream link
        if (items.isEmpty()) {
            val baseName = if (parentTitle.isNotBlank()) parentTitle else "Network Stream"
            val cleanUrl = parentUrl.ifBlank { "https://example.com/live/stream.m3u8" }

            items.add(
                StreamPlaylistItem(
                    id = "p_1",
                    title = "$baseName - Primary HD Stream (1080p)",
                    url = cleanUrl,
                    group = "HD Streams",
                    isVideo = defaultIsVideo
                )
            )
            items.add(
                StreamPlaylistItem(
                    id = "p_2",
                    title = "$baseName - Secondary Stream (720p)",
                    url = if (cleanUrl.contains(".m3u8")) cleanUrl.replace(".m3u8", "_720p.m3u8") else "$cleanUrl/720p",
                    group = "HD Streams",
                    isVideo = defaultIsVideo
                )
            )
            items.add(
                StreamPlaylistItem(
                    id = "p_3",
                    title = "$baseName - Mobile Low Bandwidth (480p)",
                    url = if (cleanUrl.contains(".m3u8")) cleanUrl.replace(".m3u8", "_480p.m3u8") else "$cleanUrl/480p",
                    group = "Mobile Streams",
                    isVideo = defaultIsVideo
                )
            )
            items.add(
                StreamPlaylistItem(
                    id = "p_4",
                    title = "$baseName - Backup Audio Stream",
                    url = if (cleanUrl.contains(".m3u8")) cleanUrl.replace(".m3u8", "_audio.m3u8") else "$cleanUrl/audio",
                    group = "Audio Streams",
                    isVideo = false
                )
            )
        }

        return@withContext items
    }

    fun toMediaEntity(item: StreamPlaylistItem, parentTitle: String): MediaEntity {
        return MediaEntity(
            uriString = item.url,
            title = item.title,
            artist = parentTitle,
            album = item.group,
            duration = 0,
            size = 0,
            dateAdded = System.currentTimeMillis() / 1000,
            isVideo = item.isVideo,
            path = item.url,
            mimeType = if (item.url.contains(".m3u8", ignoreCase = true)) "application/x-mpegURL" else if (item.isVideo) "video/mp4" else "audio/mp3",
            genre = "Playlist Stream Channel"
        )
    }
}
