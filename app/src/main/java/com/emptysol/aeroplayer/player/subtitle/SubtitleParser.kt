package com.emptysol.aeroplayer.player.subtitle

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * Universal native text subtitle parser supporting modern & legacy formats:
 * SRT, WebVTT, ASS/SSA, LRC, MicroDVD (.sub), SubViewer (.sub/.sbv),
 * SAMI (.smi/.sami), TMPlayer (.txt/.sub), and MPL2 (.mpl/.txt)
 * with charset auto-detection and error resilience.
 */
object SubtitleParser {

    val SUPPORTED_SUBTITLE_EXTENSIONS = listOf(
        "srt", "vtt", "ass", "ssa", "sub", "sbv", "smi", "sami", "lrc", "mpl", "txt"
    )

    val AUTO_DETECT_SUBTITLE_DOT_EXTENSIONS = listOf(
        ".srt", ".vtt", ".ass", ".ssa", ".sub", ".sbv", ".smi", ".sami", ".lrc", ".mpl", ".txt"
    )

    fun isNativelySupportedByExo(extensionOrUri: String): Boolean {
        val clean = extensionOrUri.substringBefore('?').substringBefore('#').lowercase()
        val ext = clean.substringAfterLast('.', clean)
        return ext in setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp", "xml")
    }

    private val BR_TAG_REGEX = Pattern.compile("(?i)<br\\s*/?>|\\[br]")
    private val HTML_TAG_REGEX = Pattern.compile("<[^>]*>")
    private val ASS_DRAWING_REGEX = Pattern.compile("\\{\\\\p[1-9]\\}.*?(\\{\\\\p0\\}|$)", Pattern.DOTALL)
    private val ASS_ALIGNMENT_REGEX = Pattern.compile("\\{[^}]*\\\\an([1-9])[^}]*\\}")
    private val ASS_OVERRIDE_REGEX = Pattern.compile("\\{[^}]*\\}")
    private val WEBVTT_VOICE_TAG_REGEX = Pattern.compile("<[vV][^>]*>|</[vV]>|<c[^>]*>|</c>|<[0-9:.]+>")
    private val SRT_TIME_REGEX = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
    private val VTT_SHORT_TIME_REGEX = Pattern.compile("(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2})[,.](\\d{1,3})")
    private val SBV_TIME_REGEX = Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3})\\s*,\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3})$")
    private val MICRODVD_LINE_REGEX = Pattern.compile("^\\{(\\d+)\\}\\{(\\d*)\\}(.*)$")
    private val MPL2_LINE_REGEX = Pattern.compile("^\\[(\\d+)\\]\\[(\\d*)\\](.*)$")
    private val TMPLAYER_LINE_REGEX = Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})[:=](.*)$")
    private val LRC_TAG_REGEX = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val LRC_OFFSET_REGEX = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE)
    private val SAMI_SYNC_SPLIT_REGEX = Pattern.compile("(?is)<SYNC\\s+[^>]*Start\\s*=\\s*[\"']?(\\d+)[\"']?[^>]*>")
    private val ASS_COORDINATE_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?[\\s,-]+-?\\d+(?:\\.\\d+)?")
    private val ASS_DRAWING_CMD_PATTERN = Pattern.compile("(?i)(?:^|[\\s\\]])([mlbspc])\\s+-?\\d+")

    /**
     * Detect whether a byte buffer is binary (e.g. VobSub MPEG PS .sub bitmap file) rather than text.
     */
    fun isBinarySubtitleBytes(bytes: ByteArray): Boolean {
        if (bytes.size >= 4 &&
            bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x01.toByte() && (bytes[3] == 0xBA.toByte() || bytes[3] == 0xBD.toByte())
        ) {
            return true
        }
        val sampleLimit = minOf(bytes.size, 512)
        if (sampleLimit < 8) return false
        var controlBytes = 0
        var zeroCount = 0
        for (i in 0 until sampleLimit) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) zeroCount++
            else if (b < 0x09 || (b in 0x0E..0x1F && b != 0x1B)) controlBytes++
        }
        // UTF-16 has many zeros ONLY on strictly alternating even/odd offsets; irregular control bytes = binary
        return controlBytes > sampleLimit / 10 && zeroCount < sampleLimit / 4
    }

    /**
     * Parse subtitle from content URI or file path silently with fallback encodings.
     */
    fun parseFromUri(context: Context, uriString: String, encodingName: String = "UTF-8"): List<SubtitleCue> {
        return try {
            val uri = Uri.parse(uriString)
            val inputStream: InputStream? = if (uri.scheme == "content" || uri.scheme == "file") {
                context.contentResolver.openInputStream(uri)
            } else {
                java.io.File(uriString).takeIf { it.exists() && it.canRead() }?.inputStream()
            }
            if (inputStream != null) {
                parseFromInputStream(inputStream, encodingName, uriString)
            } else {
                emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Parse subtitle content from an InputStream.
     */
    fun parseFromInputStream(
        inputStream: InputStream,
        encodingName: String = "UTF-8",
        fileNameHint: String? = null
    ): List<SubtitleCue> {
        return try {
            val bytes = inputStream.use { it.readBytes() }
            if (bytes.isEmpty() || isBinarySubtitleBytes(bytes)) {
                return emptyList()
            }
            val charset = resolveCharset(bytes, encodingName)
            var rawText = String(bytes, charset)
            if (rawText.startsWith("\uFEFF")) {
                rawText = rawText.removePrefix("\uFEFF")
            }
            parseText(rawText, fileNameHint)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Parse subtitle content from raw text across modern and legacy formats.
     */
    fun parseText(rawContent: String, fileNameHint: String? = null, defaultFps: Float = 25f): List<SubtitleCue> {
        if (rawContent.isBlank()) return emptyList()

        val cleanedRaw = rawContent.removePrefix("\uFEFF")
        val normalized = cleanedRaw.replace("\r\n", "\n").replace("\r", "\n")
        val hintLower = fileNameHint?.lowercase().orEmpty()
        val head = normalized.take(2048)

        return try {
            when {
                hintLower.endsWith(".ass") || hintLower.endsWith(".ssa") ||
                    head.contains("[Script Info]", ignoreCase = true) ||
                    head.contains("[Events]", ignoreCase = true) -> parseAss(normalized)

                hintLower.endsWith(".vtt") ||
                    head.trimStart().startsWith("WEBVTT", ignoreCase = true) -> parseVtt(normalized)

                hintLower.endsWith(".smi") || hintLower.endsWith(".sami") ||
                    head.contains("<SAMI", ignoreCase = true) ||
                    head.contains("<SYNC ", ignoreCase = true) -> parseSami(normalized)

                hintLower.endsWith(".lrc") -> parseLrc(normalized)

                hintLower.endsWith(".sbv") ||
                    head.contains("[INFORMATION]", ignoreCase = true) ||
                    looksLikeSubViewer(normalized) -> parseSubViewer(normalized)

                looksLikeMicroDvd(normalized) -> parseMicroDvd(normalized, defaultFps)

                looksLikeMpl2(normalized) -> parseMpl2(normalized)

                looksLikeLrc(normalized) -> parseLrc(normalized)

                looksLikeTmPlayer(normalized) -> parseTmPlayer(normalized)

                else -> {
                    val srt = parseSrt(normalized)
                    if (srt.isNotEmpty()) srt
                    else parseSubViewer(normalized).ifEmpty {
                        parseMicroDvd(normalized, defaultFps).ifEmpty {
                            parseTmPlayer(normalized)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            try {
                parseSrt(normalized)
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    private fun looksLikeMicroDvd(content: String): Boolean {
        var hits = 0
        for (line in content.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (MICRODVD_LINE_REGEX.matcher(t).matches()) {
                hits++
                if (hits >= 1) return true
            } else if (hits == 0 && !t.startsWith("#")) {
                return false
            }
        }
        return false
    }

    private fun looksLikeMpl2(content: String): Boolean {
        for (line in content.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            return MPL2_LINE_REGEX.matcher(t).matches()
        }
        return false
    }

    private fun looksLikeSubViewer(content: String): Boolean {
        var checked = 0
        for (line in content.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("[")) continue
            if (SBV_TIME_REGEX.matcher(t).matches()) return true
            checked++
            if (checked >= 8) break
        }
        return false
    }

    private fun looksLikeTmPlayer(content: String): Boolean {
        var checked = 0
        for (line in content.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (TMPLAYER_LINE_REGEX.matcher(t).matches()) return true
            checked++
            if (checked >= 5) break
        }
        return false
    }

    private fun looksLikeLrc(content: String): Boolean {
        var matches = 0
        for (line in content.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (LRC_TAG_REGEX.matcher(t).find()) {
                matches++
                if (matches >= 2) return true
            }
        }
        return matches >= 1
    }

    /**
     * Parse SubRip (.srt) subtitle format with tolerant blank-line detection.
     */
    private fun parseSrt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = content.split(Regex("\\n[ \\t]*\\n+"))

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var timeLineIndex = -1
            var startMs = -1L
            var endMs = -1L

            for (i in lines.indices) {
                val matcher = SRT_TIME_REGEX.matcher(lines[i])
                if (matcher.find()) {
                    timeLineIndex = i
                    startMs = parseTimecode(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4))
                    endMs = parseTimecode(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8))
                    break
                }
            }

            if (timeLineIndex != -1 && startMs >= 0L && endMs > startMs && timeLineIndex < lines.size - 1) {
                val alignment = extractAssAlignment(lines.subList(timeLineIndex + 1, lines.size).joinToString("\n"))
                val fullText = lines.subList(timeLineIndex + 1, lines.size).joinToString("\n")
                val cleanText = cleanCueText(fullText)
                if (cleanText.isNotBlank()) {
                    cues.add(SubtitleCue(startMs, endMs, cleanText, cleanText, alignment))
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * Parse WebVTT (.vtt) subtitle format including multi-line STYLE/NOTE blocks and short timestamps.
     */
    private fun parseVtt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.lines().map { it.trim() }
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.isEmpty() || line.startsWith("WEBVTT", ignoreCase = true)) {
                i++
                continue
            }
            if (line.startsWith("NOTE", ignoreCase = true) ||
                line.startsWith("STYLE", ignoreCase = true) ||
                line.startsWith("REGION", ignoreCase = true)
            ) {
                // Skip entire metadata/comment block until blank line
                while (i < lines.size && lines[i].isNotEmpty()) {
                    i++
                }
                continue
            }

            var startMs = -1L
            var endMs = -1L

            val matcher = SRT_TIME_REGEX.matcher(line)
            if (matcher.find()) {
                startMs = parseTimecode(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4))
                endMs = parseTimecode(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8))
            } else {
                val shortMatcher = VTT_SHORT_TIME_REGEX.matcher(line)
                if (shortMatcher.find()) {
                    startMs = parseShortTimecode(shortMatcher.group(1), shortMatcher.group(2), shortMatcher.group(3))
                    endMs = parseShortTimecode(shortMatcher.group(4), shortMatcher.group(5), shortMatcher.group(6))
                }
            }

            if (startMs >= 0L && endMs > startMs) {
                i++
                val textBuilder = StringBuilder()
                while (i < lines.size && lines[i].isNotEmpty()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                    textBuilder.append(lines[i])
                    i++
                }
                val text = cleanCueText(textBuilder.toString())
                if (text.isNotBlank()) {
                    cues.add(SubtitleCue(startMs, endMs, text, text))
                }
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * Parse Advanced SubStation Alpha (.ass / .ssa) subtitle format.
     */
    private fun parseAss(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.lines().map { it.trim() }

        var inEvents = false
        var formatFields: List<String> = emptyList()
        var textIndex = -1
        var startIndex = -1
        var endIndex = -1

        for (line in lines) {
            if (line.startsWith("[Events]", ignoreCase = true)) {
                inEvents = true
                continue
            }
            if (line.startsWith("[") && inEvents) {
                inEvents = false
                continue
            }

            if (inEvents) {
                if (line.startsWith("Format:", ignoreCase = true)) {
                    val rawFormat = line.substringAfter(":").trim()
                    formatFields = rawFormat.split(",").map { it.trim().lowercase() }
                    startIndex = formatFields.indexOf("start")
                    endIndex = formatFields.indexOf("end")
                    textIndex = formatFields.indexOf("text")
                } else if (line.startsWith("Dialogue:", ignoreCase = true)) {
                    val rawDialogue = line.substringAfter(":").trim()
                    val expectedParts = if (formatFields.isNotEmpty()) formatFields.size else 10
                    val parts = rawDialogue.split(",", limit = expectedParts)

                    val startStr = if (startIndex in parts.indices) parts[startIndex].trim() else parts.getOrNull(1)?.trim()
                    val endStr = if (endIndex in parts.indices) parts[endIndex].trim() else parts.getOrNull(2)?.trim()
                    val textPart = if (textIndex in parts.indices) parts[textIndex] else parts.lastOrNull() ?: ""

                    if (!startStr.isNullOrBlank() && !endStr.isNullOrBlank()) {
                        val startMs = parseAssTimecode(startStr)
                        val endMs = parseAssTimecode(endStr)
                        if (startMs >= 0L && endMs > startMs) {
                            val alignment = extractAssAlignment(textPart)
                            val cleanText = cleanCueText(textPart)
                            if (cleanText.isNotBlank()) {
                                cues.add(SubtitleCue(startMs, endMs, cleanText, cleanText, alignment))
                            }
                        }
                    }
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * Parse MicroDVD frame-based (.sub) subtitle format: `{startFrame}{endFrame}Text|Line2`.
     * Supports optional header FPS marker `{1}{1}23.976` or `{0}{0}25`.
     */
    private fun parseMicroDvd(content: String, defaultFps: Float = 25f): List<SubtitleCue> {
        var fps = defaultFps.coerceIn(10f, 120f)
        val rawCues = mutableListOf<Triple<Long, Long, String>>()

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val m = MICRODVD_LINE_REGEX.matcher(line)
            if (!m.matches()) continue

            val startFrame = m.group(1)?.toLongOrNull() ?: continue
            val endFrameRaw = m.group(2)?.toLongOrNull()
            val body = m.group(3)?.trim().orEmpty()

            // Optional FPS header cue
            if (rawCues.isEmpty() && startFrame <= 1L && (endFrameRaw == null || endFrameRaw <= 1L)) {
                val parsedFps = body.replace(",", ".").toFloatOrNull()
                if (parsedFps != null && parsedFps in 10f..120f) {
                    fps = parsedFps
                    continue
                }
            }

            if (body.isNotEmpty()) {
                rawCues.add(Triple(startFrame, endFrameRaw ?: -1L, body))
            }
        }

        val msPerFrame = 1000.0 / fps.toDouble()
        val result = ArrayList<SubtitleCue>(rawCues.size)
        for (idx in rawCues.indices) {
            val (startFrame, endFrame, body) = rawCues[idx]
            val startMs = (startFrame * msPerFrame).toLong().coerceAtLeast(0L)
            val resolvedEndFrame = when {
                endFrame > startFrame -> endFrame
                idx + 1 < rawCues.size -> maxOf(startFrame + 1, rawCues[idx + 1].first - 1)
                else -> startFrame + (fps * 4).toLong()
            }
            val endMs = (resolvedEndFrame * msPerFrame).toLong().coerceAtLeast(startMs + 250L)
            val cleanText = cleanCueText(body.replace("|", "\n"))
            if (cleanText.isNotBlank()) {
                result.add(SubtitleCue(startMs, endMs, cleanText, cleanText))
            }
        }
        return result.sortedBy { it.startMs }
    }

    /**
     * Parse SubViewer (.sbv / .sub) format: `H:MM:SS.cc,H:MM:SS.cc` followed by `[br]`-delimited text lines.
     */
    private fun parseSubViewer(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isEmpty() || line.startsWith("[")) {
                i++
                continue
            }
            val m = SBV_TIME_REGEX.matcher(line)
            if (m.matches()) {
                val startMs = parseTimecode(m.group(1)!!, m.group(2)!!, m.group(3)!!, m.group(4)!!)
                val endMs = parseTimecode(m.group(5)!!, m.group(6)!!, m.group(7)!!, m.group(8)!!)
                i++
                val textBuilder = StringBuilder()
                while (i < lines.size && lines[i].isNotEmpty() && !SBV_TIME_REGEX.matcher(lines[i]).matches()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                    textBuilder.append(lines[i])
                    i++
                }
                if (startMs >= 0L && endMs > startMs) {
                    val cleanText = cleanCueText(textBuilder.toString())
                    if (cleanText.isNotBlank()) {
                        cues.add(SubtitleCue(startMs, endMs, cleanText, cleanText))
                    }
                }
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * Parse SAMI (.smi / .sami) legacy captioning format (`<SYNC Start=ms><P>...</P>`).
     */
    private fun parseSami(content: String): List<SubtitleCue> {
        // Remove <STYLE>...</STYLE> and <HEAD>...</HEAD> blocks first
        val stripped = content
            .replace(Regex("(?is)<STYLE[^>]*>.*?</STYLE>"), "")
            .replace(Regex("(?is)<HEAD[^>]*>.*?</HEAD>"), "")

        val syncMatcher = SAMI_SYNC_SPLIT_REGEX.matcher(stripped)
        val syncPoints = mutableListOf<Pair<Long, Int>>() // startMs to contentStartIndex
        val tagEndIndices = mutableListOf<Int>()

        while (syncMatcher.find()) {
            val ms = syncMatcher.group(1)?.toLongOrNull() ?: continue
            syncPoints.add(ms to syncMatcher.end())
            tagEndIndices.add(syncMatcher.start())
        }
        if (syncPoints.isEmpty()) return emptyList()

        val cues = mutableListOf<SubtitleCue>()
        for (i in syncPoints.indices) {
            val (startMs, sliceStart) = syncPoints[i]
            val sliceEnd = if (i + 1 < tagEndIndices.size) tagEndIndices[i + 1] else stripped.length
            val rawSegment = stripped.substring(sliceStart, sliceEnd)
                .replace(Regex("(?is)</?BODY[^>]*>|</?SAMI[^>]*>"), "")
            val clean = cleanCueText(rawSegment)
            if (clean.isNotBlank()) {
                val endMs = if (i + 1 < syncPoints.size && syncPoints[i + 1].first > startMs) {
                    syncPoints[i + 1].first
                } else {
                    startMs + 5000L
                }
                cues.add(SubtitleCue(startMs, endMs, clean, clean))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * Parse LRC synchronized lyric/subtitle format (`[mm:ss.xx]Text`, multi-timestamp lines, and `[offset:]`).
     */
    private fun parseLrc(content: String): List<SubtitleCue> {
        var offsetMs = 0L
        val entries = mutableListOf<Pair<Long, String>>()

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val offsetMatch = LRC_OFFSET_REGEX.matcher(line)
            if (offsetMatch.find()) {
                offsetMs = offsetMatch.group(1)?.toLongOrNull() ?: 0L
                continue
            }

            val tagMatcher = LRC_TAG_REGEX.matcher(line)
            val timestamps = mutableListOf<Long>()
            var lastTagEnd = 0
            while (tagMatcher.find()) {
                val mins = tagMatcher.group(1)?.toLongOrNull() ?: continue
                val secs = tagMatcher.group(2)?.toLongOrNull() ?: continue
                val fracRaw = tagMatcher.group(3)
                val millis = when {
                    fracRaw.isNullOrEmpty() -> 0L
                    fracRaw.length == 1 -> fracRaw.toLong() * 100L
                    fracRaw.length == 2 -> fracRaw.toLong() * 10L
                    else -> fracRaw.take(3).toLong()
                }
                val ts = (mins * 60_000L) + (secs * 1000L) + millis
                timestamps.add(ts)
                lastTagEnd = tagMatcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val lyricBody = cleanCueText(line.substring(lastTagEnd))
                if (lyricBody.isNotBlank()) {
                    for (ts in timestamps) {
                        val adjusted = (ts - offsetMs).coerceAtLeast(0L)
                        entries.add(adjusted to lyricBody)
                    }
                }
            }
        }

        if (entries.isEmpty()) return emptyList()
        entries.sortBy { it.first }

        val cues = ArrayList<SubtitleCue>(entries.size)
        for (i in entries.indices) {
            val (startMs, text) = entries[i]
            val endMs = if (i + 1 < entries.size && entries[i + 1].first > startMs) {
                minOf(entries[i + 1].first, startMs + 10_000L)
            } else {
                startMs + 5000L
            }
            cues.add(SubtitleCue(startMs, maxOf(endMs, startMs + 300L), text, text))
        }
        return cues
    }

    /**
     * Parse TMPlayer legacy format (`HH:MM:SS:Line|Line2` or `H:MM:SS=Line`).
     */
    private fun parseTmPlayer(content: String): List<SubtitleCue> {
        val rawCues = mutableListOf<Pair<Long, String>>()
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val m = TMPLAYER_LINE_REGEX.matcher(line)
            if (m.matches()) {
                val h = m.group(1)?.toLongOrNull() ?: continue
                val min = m.group(2)?.toLongOrNull() ?: continue
                val sec = m.group(3)?.toLongOrNull() ?: continue
                val startMs = (h * 3_600_000L) + (min * 60_000L) + (sec * 1000L)
                val body = cleanCueText((m.group(4) ?: "").replace("|", "\n"))
                if (body.isNotBlank()) {
                    rawCues.add(startMs to body)
                }
            }
        }
        if (rawCues.isEmpty()) return emptyList()
        rawCues.sortBy { it.first }

        val result = ArrayList<SubtitleCue>(rawCues.size)
        for (i in rawCues.indices) {
            val (startMs, text) = rawCues[i]
            val nextStart = if (i + 1 < rawCues.size) rawCues[i + 1].first else startMs + 5000L
            val endMs = if (nextStart > startMs) minOf(nextStart - 1L, startMs + 6000L) else startMs + 4000L
            result.add(SubtitleCue(startMs, maxOf(endMs, startMs + 500L), text, text))
        }
        return result
    }

    /**
     * Parse MPL2 legacy format (`[startDeciSec][endDeciSec]Text|Line2`).
     */
    private fun parseMpl2(content: String): List<SubtitleCue> {
        val rawCues = mutableListOf<Triple<Long, Long, String>>()
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val m = MPL2_LINE_REGEX.matcher(line)
            if (m.matches()) {
                val startDs = m.group(1)?.toLongOrNull() ?: continue
                val endDs = m.group(2)?.toLongOrNull() ?: (startDs + 40L)
                val startMs = startDs * 100L
                val endMs = maxOf(endDs * 100L, startMs + 300L)
                val rawBody = (m.group(3) ?: "")
                    .split("|")
                    .joinToString("\n") { segment -> segment.trimStart().removePrefix("/") }
                val body = cleanCueText(rawBody)
                if (body.isNotBlank()) {
                    rawCues.add(Triple(startMs, endMs, body))
                }
            }
        }
        return rawCues.map { (s, e, t) -> SubtitleCue(s, e, t, t) }.sortedBy { it.startMs }
    }

    private fun extractAssAlignment(text: String): SubtitleAlignment {
        val m = ASS_ALIGNMENT_REGEX.matcher(text)
        if (m.find()) {
            return when (m.group(1)) {
                "8" -> SubtitleAlignment.TOP_CENTER
                "5" -> SubtitleAlignment.CENTER
                "1", "4", "7" -> SubtitleAlignment.BOTTOM_LEFT
                "3", "6", "9" -> SubtitleAlignment.BOTTOM_RIGHT
                else -> SubtitleAlignment.BOTTOM_CENTER
            }
        }
        return SubtitleAlignment.BOTTOM_CENTER
    }

    private fun parseTimecode(h: String, m: String, s: String, ms: String): Long {
        return try {
            val hours = h.trim().toLong()
            val mins = m.trim().toLong()
            val secs = s.trim().toLong()
            val cleanMs = ms.trim().filter { it.isDigit() }
            val millis = cleanMs.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            (hours * 3600000L) + (mins * 60000L) + (secs * 1000L) + millis
        } catch (_: Exception) {
            -1L
        }
    }

    private fun parseShortTimecode(m: String, s: String, ms: String): Long {
        return try {
            val mins = m.trim().toLong()
            val secs = s.trim().toLong()
            val cleanMs = ms.trim().filter { it.isDigit() }
            val millis = cleanMs.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            (mins * 60000L) + (secs * 1000L) + millis
        } catch (_: Exception) {
            -1L
        }
    }

    private fun parseAssTimecode(timeStr: String): Long {
        return try {
            val parts = timeStr.trim().split(":")
            if (parts.size >= 3) {
                val h = parts[0].trim().toLong()
                val m = parts[1].trim().toLong()
                val secParts = parts[2].trim().split(".")
                val s = secParts[0].trim().toLong()
                val csStr = if (secParts.size > 1) secParts[1].trim().filter { it.isDigit() } else "0"
                val cs = csStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                (h * 3600000L) + (m * 60000L) + (s * 1000L) + cs
            } else {
                -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Clean any subtitle cue text from ASS/SSA script tags, WebVTT tags, HTML spans, and MicroDVD/SubViewer markup.
     */
    fun cleanCueText(text: String): String {
        if (text.isBlank()) return ""
        // Strip null characters and unprintable control characters
        var cleaned = text.replace("\u0000", "")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
        // 0. Preserve explicit HTML/SubViewer line breaks BEFORE stripping tags
        cleaned = BR_TAG_REGEX.matcher(cleaned).replaceAll("\n")
        // 1. Remove ASS drawing vector blocks {\p1}m...{\p0}
        cleaned = ASS_DRAWING_REGEX.matcher(cleaned).replaceAll("")
        // 2. Remove ASS & MicroDVD override tags {\...} / {y:i} / {c:$...}
        cleaned = ASS_OVERRIDE_REGEX.matcher(cleaned).replaceAll("")
        // 3. Remove WebVTT voice/cue tags <v Speaker>, <c.yellow>, <00:00.000>
        cleaned = WEBVTT_VOICE_TAG_REGEX.matcher(cleaned).replaceAll("")
        // 4. Remove HTML spans/tags <b>, <i>, <font color="...">
        cleaned = HTML_TAG_REGEX.matcher(cleaned).replaceAll("")
        // 5. Replace ASS/VTT whitespace escape sequences
        cleaned = cleaned
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .replace("\\t", " ")
        // 6. Decode standard XML/HTML entities & trim each line cleanly
        cleaned = unescapeHtmlEntities(cleaned)
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()

        // 7. Suppress binary bitstream junk artifacts (e.g. "0101010101", "01 01 01", raw bitstreams)
        if (isBinaryOrNoise(cleaned)) {
            return ""
        }

        // 8. Suppress ASS drawing vector coordinate streams and typesetting graphics
        if (isAssDrawingOrCoordinateArtifact(cleaned)) {
            return ""
        }

        return cleaned
    }

    private fun isAssDrawingOrCoordinateArtifact(text: String): Boolean {
        if (text.length < 12) return false

        val matcher = ASS_COORDINATE_PATTERN.matcher(text)
        var coordCount = 0
        while (matcher.find()) {
            coordCount++
            if (coordCount >= 3) return true
        }

        if (ASS_DRAWING_CMD_PATTERN.matcher(text).find() && coordCount >= 1) {
            return true
        }

        val nonSpace = text.filter { !it.isWhitespace() }
        if (nonSpace.length >= 25) {
            val numSymbolCount = nonSpace.count { it.isDigit() || it == '.' || it == '-' || it == ',' }
            if (numSymbolCount.toFloat() / nonSpace.length > 0.60f) {
                return true
            }
        }

        return false
    }

    private fun isBinaryOrNoise(text: String): Boolean {
        if (text.length < 6) return false
        val noSpaces = text.replace(" ", "").replace("\t", "").replace("\n", "")
        if (noSpaces.length >= 6 && noSpaces.all { it == '0' || it == '1' }) {
            return true
        }
        val zeroOneCount = noSpaces.count { it == '0' || it == '1' }
        if (noSpaces.length >= 10 && zeroOneCount.toFloat() / noSpaces.length > 0.85f) {
            return true
        }
        return false
    }

    private fun unescapeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
    }

    private fun resolveCharset(bytes: ByteArray, preferredEncoding: String): Charset {
        // 1. Detect Byte Order Mark (BOM)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }

        // 2. Detect UTF-16 without BOM (Null-byte pattern)
        if (bytes.size >= 16) {
            var nullOnOdd = 0
            var nullOnEven = 0
            val sampleLimit = minOf(bytes.size, 1024)
            for (i in 0 until sampleLimit) {
                if (bytes[i] == 0.toByte()) {
                    if (i % 2 == 1) nullOnOdd++ else nullOnEven++
                }
            }
            if (nullOnOdd > sampleLimit / 4 && nullOnEven < 5) {
                return Charsets.UTF_16LE
            }
            if (nullOnEven > sampleLimit / 4 && nullOnOdd < 5) {
                return Charsets.UTF_16BE
            }
        }

        // 3. Try user preferred encoding
        if (preferredEncoding.isNotBlank() && !preferredEncoding.equals("Auto", ignoreCase = true)) {
            try {
                val normalized = when {
                    preferredEncoding.contains("1252", ignoreCase = true) -> "windows-1252"
                    preferredEncoding.contains("8859-1", ignoreCase = true) -> "ISO-8859-1"
                    preferredEncoding.contains("UTF-16LE", ignoreCase = true) -> "UTF-16LE"
                    preferredEncoding.contains("UTF-16BE", ignoreCase = true) -> "UTF-16BE"
                    preferredEncoding.contains("UTF-16", ignoreCase = true) -> "UTF-16"
                    preferredEncoding.contains("GBK", ignoreCase = true) || preferredEncoding.contains("GB2312", ignoreCase = true) -> "GBK"
                    preferredEncoding.contains("Shift", ignoreCase = true) -> "Shift_JIS"
                    preferredEncoding.contains("Big5", ignoreCase = true) -> "Big5"
                    preferredEncoding.contains("EUC-KR", ignoreCase = true) -> "EUC-KR"
                    else -> preferredEncoding
                }
                if (Charset.isSupported(normalized)) {
                    return Charset.forName(normalized)
                }
            } catch (_: Exception) {
                // Fallback
            }
        }

        // 4. Validate UTF-8 compliance, fallback to Windows-1252 / ISO-8859-1 if invalid
        if (!isValidUtf8(bytes)) {
            try {
                return Charset.forName("windows-1252")
            } catch (_: Exception) {
                return Charsets.ISO_8859_1
            }
        }

        return Charsets.UTF_8
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b in 0x00..0x7F -> i++
                b in 0xC2..0xDF -> {
                    if (i + 1 >= len) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    if (b2 !in 0x80..0xBF) return false
                    i += 2
                }
                b in 0xE0..0xEF -> {
                    if (i + 2 >= len) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF) return false
                    i += 3
                }
                b in 0xF0..0xF4 -> {
                    if (i + 3 >= len) return false
                    val b2 = bytes[i + 1].toInt() and 0xFF
                    val b3 = bytes[i + 2].toInt() and 0xFF
                    val b4 = bytes[i + 3].toInt() and 0xFF
                    if (b2 !in 0x80..0xBF || b3 !in 0x80..0xBF || b4 !in 0x80..0xBF) return false
                    i += 4
                }
                else -> return false
            }
        }
        return true
    }
}
