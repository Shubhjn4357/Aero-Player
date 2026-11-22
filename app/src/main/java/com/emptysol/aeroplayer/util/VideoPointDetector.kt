package com.emptysol.aeroplayer.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

enum class VideoPointType(val label: String, val badgeColorHex: Long) {
    PROLOGUE("Prologue / Intro", 0xFF6366F1),          // Indigo
    RECAP("Recap / Cold Open", 0xFF8B5CF6),             // Violet
    TITLE_CARD("Title Sequence", 0xFF3B82F6),           // Blue
    CHAPTER("Main Feature", 0xFF10B981),                // Emerald Green
    INTERMISSION("Midpoint / Intermission", 0xFFF59E0B),// Amber
    CLIMAX("Climax & Finale", 0xFFEF4444),              // Rose / Red
    CREDITS("Rolling Credits", 0xFF64748B),             // Slate
    POST_CREDIT("Post-Credit Scene", 0xFFEC4899)        // Pink
}

data class VideoPoint(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: VideoPointType,
    val startMs: Long,
    val endMs: Long,
    val isSkippable: Boolean = false,
    val skipTargetMs: Long = endMs,
    val isNativeChapter: Boolean = false
) {
    fun formatRange(): String {
        return "${formatTime(startMs)} - ${formatTime(endMs)}"
    }

    fun formatDuration(): String {
        val durationSec = ((endMs - startMs) / 1000).coerceAtLeast(0)
        val m = durationSec / 60
        val s = durationSec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}

object VideoPointDetector {

    /**
     * Maps native media chapters parsed directly from MKV/MP4 files (LibVLC or Media3)
     * into VideoPoints, automatically recognizing prologue/intro, recap, opening/ending,
     * credits, and post-credit tags.
     */
    fun mapNativeChapters(
        nativeChapters: List<NativeChapterRaw>,
        durationMs: Long
    ): List<VideoPoint> {
        if (nativeChapters.isEmpty()) return emptyList()

        // Sort by start offset
        val sorted = nativeChapters.sortedBy { it.startTimeMs }
        val points = mutableListOf<VideoPoint>()

        for (i in sorted.indices) {
            val curr = sorted[i]
            val nextStart = if (i < sorted.size - 1) sorted[i + 1].startTimeMs else durationMs
            val effectiveEnd = if (curr.durationMs > 0) {
                (curr.startTimeMs + curr.durationMs).coerceAtMost(durationMs)
            } else {
                nextStart.coerceAtMost(durationMs)
            }

            if (effectiveEnd <= curr.startTimeMs) continue

            val lowerName = curr.name.trim().lowercase(Locale.US)

            // Categorize based on native chapter title heuristics
            val (type, isSkippable, skipTarget) = when {
                lowerName.contains("prologue") || lowerName.contains("cold open") || lowerName.contains("recap") -> {
                    Triple(VideoPointType.PROLOGUE, true, effectiveEnd)
                }
                lowerName.contains("intro") || lowerName.contains("opening") || lowerName.contains("op") -> {
                    Triple(VideoPointType.PROLOGUE, true, effectiveEnd)
                }
                lowerName.contains("title") -> {
                    Triple(VideoPointType.TITLE_CARD, false, effectiveEnd)
                }
                lowerName.contains("post-credit") || lowerName.contains("post credit") || lowerName.contains("stinger") || lowerName.contains("after credit") -> {
                    Triple(VideoPointType.POST_CREDIT, true, effectiveEnd)
                }
                lowerName.contains("credit") || lowerName.contains("ending") || lowerName.contains("ed") || lowerName.contains("outro") -> {
                    Triple(VideoPointType.CREDITS, true, effectiveEnd)
                }
                lowerName.contains("intermission") || lowerName.contains("break") -> {
                    Triple(VideoPointType.INTERMISSION, false, effectiveEnd)
                }
                lowerName.contains("climax") || lowerName.contains("finale") -> {
                    Triple(VideoPointType.CLIMAX, false, effectiveEnd)
                }
                else -> {
                    // Check if it's the very first chapter under 3 minutes, or the very last chapter
                    if (i == 0 && (effectiveEnd - curr.startTimeMs) in 10_000L..180_000L && lowerName.contains("start")) {
                        Triple(VideoPointType.PROLOGUE, true, effectiveEnd)
                    } else if (i == sorted.size - 1 && (durationMs - curr.startTimeMs) in 15_000L..300_000L && (lowerName.contains("end") || lowerName.contains("credit"))) {
                        Triple(VideoPointType.CREDITS, true, effectiveEnd)
                    } else {
                        Triple(VideoPointType.CHAPTER, false, effectiveEnd)
                    }
                }
            }

            points.add(
                VideoPoint(
                    id = "native_ch_${i + 1}",
                    title = curr.name.ifBlank { "Chapter ${i + 1}" },
                    subtitle = "Native Media Chapter ${i + 1} (${type.label})",
                    type = type,
                    startMs = curr.startTimeMs,
                    endMs = effectiveEnd,
                    isSkippable = isSkippable,
                    skipTargetMs = skipTarget,
                    isNativeChapter = true
                )
            )
        }

        return points
    }

    /**
     * Generic data holder for raw chapters extracted from video files.
     */
    data class NativeChapterRaw(
        val name: String,
        val startTimeMs: Long,
        val durationMs: Long = 0L
    )

    /**
     * Returns genuine key points / chapters detected from embedded file metadata or LibVLC tracks.
     * Never fabricates artificial percentage splits; returns emptyList() when no real chapters exist.
     */
    fun detectPoints(
        durationMs: Long,
        customPrologueSec: Int? = null,
        customPostCreditSec: Int? = null,
        nativeChapters: List<NativeChapterRaw> = emptyList()
    ): List<VideoPoint> {
        if (nativeChapters.isEmpty()) return emptyList()
        val mapped = mapNativeChapters(nativeChapters, durationMs.coerceAtLeast(1L))
        if (mapped.isEmpty() || customPrologueSec == null) return mapped
        // Allow optional custom prologue duration hint only for an actual opening/prologue chapter
        return mapped.mapIndexed { idx, pt ->
            if (idx == 0 && pt.type == VideoPointType.PROLOGUE) {
                val capped = (customPrologueSec.toLong() * 1000L).coerceIn(1_000L, durationMs.coerceAtLeast(1L))
                pt.copy(endMs = capped, skipTargetMs = capped)
            } else pt
        }
    }

    /**
     * Probes video file container metadata (MP4/MKV/WebM) on IO thread to extract genuine
     * embedded chapter markers and timestamps directly from the media container.
     */
    suspend fun probeVideoMetadataChapters(
        context: Context,
        uriString: String,
        durationMs: Long,
        path: String? = null
    ): List<NativeChapterRaw> = withContext(Dispatchers.IO) {
        val uri = try { Uri.parse(uriString) } catch (e: Exception) { return@withContext emptyList() }
        val results = mutableListOf<NativeChapterRaw>()

        try {
            val extractor = MediaExtractor()
            if (uri.scheme == "content" || uri.scheme == "file") {
                extractor.setDataSource(context, uri, null)
            } else if (!path.isNullOrBlank() && java.io.File(path).let { it.exists() && it.canRead() }) {
                extractor.setDataSource(path)
            } else if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
                extractor.setDataSource(uriString)
            }
            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                val titleMeta = try { format.getString("title") ?: "" } catch (_: Throwable) { "" }
                val isChapterTrack = mime.startsWith("text/") ||
                                     mime.contains("quicktime") ||
                                     mime.contains("tx3g") ||
                                     format.containsKey("chapter") ||
                                     titleMeta.contains("chapter", ignoreCase = true)
                // Skip pure external/subtitle tracks unless labeled or MP4 chapter text
                if (mime.contains("subrip", ignoreCase = true) && !titleMeta.contains("chapter", ignoreCase = true)) {
                    continue
                }
                if (isChapterTrack) {
                    extractor.selectTrack(i)
                    val buffer = java.nio.ByteBuffer.allocate(1024)
                    var sampleCount = 0
                    while (sampleCount < 64) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        val sampleTimeUs = extractor.sampleTime
                        val timeMs = sampleTimeUs / 1000L
                        val text = try {
                            val bytes = ByteArray(sampleSize)
                            buffer.get(bytes)
                            // Skip 2-byte length prefix in tx3g timed text if present
                            if (mime.contains("tx3g") && bytes.size > 2) {
                                val textLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                                if (textLen in 1..(bytes.size - 2)) {
                                    String(bytes, 2, textLen, java.nio.charset.StandardCharsets.UTF_8).trim()
                                } else {
                                    String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim()
                                }
                            } else {
                                String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim()
                            }
                        } catch (e: Exception) {
                            "Chapter ${sampleCount + 1}"
                        }
                        if (timeMs >= 0 && (results.isEmpty() || kotlin.math.abs(timeMs - results.last().startTimeMs) > 1000)) {
                            val cleanName = text.replace(Regex("[\\x00-\\x1F]"), "").trim()
                            val name = if (cleanName.isNotBlank() && cleanName.length < 100) cleanName else "Chapter ${results.size + 1}"
                            results.add(NativeChapterRaw(name = name, startTimeMs = timeMs))
                        }
                        sampleCount++
                        if (!extractor.advance()) break
                    }
                    extractor.unselectTrack(i)
                    if (results.size >= 2) break
                }
            }
            extractor.release()
        } catch (_: Throwable) {}

        // Also inspect local MP4 'chpl' chapter list box if present in moov/udta
        if (results.isEmpty()) {
            val localPath = when {
                !path.isNullOrBlank() -> path
                uri.scheme == "file" -> uri.path
                uriString.startsWith("/") -> uriString
                else -> null
            }
            if (!localPath.isNullOrBlank()) {
                val file = java.io.File(localPath)
                if (file.exists() && file.canRead() && file.length() > 32) {
                    val lower = file.name.lowercase(Locale.US)
                    if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".mov") || lower.endsWith(".m4a")) {
                        results.addAll(parseMp4ChplChapters(file))
                    }
                }
            }
        }

        if (results.size >= 1) {
            val sorted = results.sortedBy { it.startTimeMs }
            val effectiveTotal = if (durationMs > 0L) durationMs else (sorted.last().startTimeMs + 10_000L)
            return@withContext sorted.mapIndexed { idx, ch ->
                val nextStart = if (idx < sorted.size - 1) sorted[idx + 1].startTimeMs else effectiveTotal
                val dur = (nextStart - ch.startTimeMs).coerceAtLeast(1_000L)
                ch.copy(durationMs = dur)
            }
        }

        emptyList()
    }

    private fun parseMp4ChplChapters(file: java.io.File): List<NativeChapterRaw> {
        val out = mutableListOf<NativeChapterRaw>()
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                fun scanAtoms(start: Long, end: Long, depth: Int) {
                    if (depth > 4 || out.isNotEmpty()) return
                    var offset = start
                    while (offset + 8 <= end) {
                        raf.seek(offset)
                        val sizeInt = raf.readInt()
                        val typeBytes = ByteArray(4)
                        raf.readFully(typeBytes)
                        val type = String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII)
                        var headerSize = 8L
                        var atomSize = sizeInt.toLong() and 0xFFFFFFFFL
                        if (atomSize == 1L && offset + 16 <= end) {
                            atomSize = raf.readLong()
                            headerSize = 16L
                        } else if (atomSize == 0L) {
                            atomSize = end - offset
                        }
                        if (atomSize < headerSize || offset + atomSize > end) break
                        val payloadStart = offset + headerSize
                        val atomEnd = offset + atomSize
                        when (type) {
                            "moov", "udta" -> scanAtoms(payloadStart, atomEnd, depth + 1)
                            "chpl" -> {
                                val chplLen = (atomEnd - payloadStart).toInt().coerceAtMost(64 * 1024)
                                if (chplLen > 9) {
                                    raf.seek(payloadStart)
                                    val data = ByteArray(chplLen)
                                    raf.readFully(data)
                                    val count = data[8].toInt() and 0xFF
                                    var p = 9
                                    for (c in 0 until count) {
                                        if (p + 9 > data.size) break
                                        var ts100ns = 0L
                                        for (b in 0 until 8) {
                                            ts100ns = (ts100ns shl 8) or (data[p + b].toLong() and 0xFF)
                                        }
                                        val titleLen = data[p + 8].toInt() and 0xFF
                                        p += 9
                                        if (p + titleLen > data.size) break
                                        val title = String(data, p, titleLen, java.nio.charset.StandardCharsets.UTF_8).trim()
                                        p += titleLen
                                        val startMs = (ts100ns / 10_000L).coerceAtLeast(0L)
                                        out.add(NativeChapterRaw(title.ifBlank { "Chapter ${out.size + 1}" }, startMs))
                                    }
                                }
                                return
                            }
                        }
                        offset = atomEnd
                    }
                }
                scanAtoms(0L, fileLen, 0)
            }
        } catch (_: Throwable) {}
        return out
    }

    /**
     * Determines which point is currently active based on playback position.
     */
    fun getActivePoint(points: List<VideoPoint>, positionMs: Long): VideoPoint? {
        return points.firstOrNull { positionMs in it.startMs until it.endMs }
            ?: points.lastOrNull { positionMs >= it.startMs }
    }

    /**
     * Detects if the current playback position is in a skippable designated time window
     * (e.g. Prologue or Rolling Credits before post-credit scene).
     */
    fun getActiveSkippablePoint(points: List<VideoPoint>, positionMs: Long): VideoPoint? {
        return points.firstOrNull { it.isSkippable && positionMs in it.startMs until it.endMs }
    }
}
