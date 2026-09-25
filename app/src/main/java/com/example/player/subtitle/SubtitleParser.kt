package com.example.player.subtitle

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * Universal native text subtitle parser supporting SRT, WebVTT, ASS/SSA, and LRC with charset auto-detection
 * and silent error resilience.
 */
object SubtitleParser {

    private val HTML_TAG_REGEX = Pattern.compile("<[^>]*>")
    private val ASS_DRAWING_REGEX = Pattern.compile("\\{\\\\p[1-9]\\}.*?(\\{\\\\p0\\}|$)", Pattern.DOTALL)
    private val ASS_OVERRIDE_REGEX = Pattern.compile("\\{[^}]*\\}")
    private val WEBVTT_VOICE_TAG_REGEX = Pattern.compile("<[vV][^>]*>|</[vV]>|<c[^>]*>|</c>|<[0-9:.]+>")
    private val SRT_TIME_REGEX = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
    private val VTT_SHORT_TIME_REGEX = Pattern.compile("(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2})[,.](\\d{1,3})")
    private val ASS_COORDINATE_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?[\\s,-]+-?\\d+(?:\\.\\d+)?")
    private val ASS_DRAWING_CMD_PATTERN = Pattern.compile("(?i)(?:^|[\\s\\]])([mlbspc])\\s+-?\\d+")

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
        } catch (e: Throwable) {
            // Silently return empty list on any I/O or access error
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
            val charset = resolveCharset(bytes, encodingName)
            val rawText = String(bytes, charset)
            parseText(rawText, fileNameHint)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * Parse subtitle content from raw text.
     */
    fun parseText(rawContent: String, fileNameHint: String? = null): List<SubtitleCue> {
        if (rawContent.isBlank()) return emptyList()

        val normalized = rawContent.replace("\r\n", "\n").replace("\r", "\n")
        val isAss = fileNameHint?.endsWith(".ass", ignoreCase = true) == true ||
                fileNameHint?.endsWith(".ssa", ignoreCase = true) == true ||
                normalized.contains("[Script Info]") ||
                normalized.contains("[Events]")

        val isVtt = fileNameHint?.endsWith(".vtt", ignoreCase = true) == true ||
                normalized.startsWith("WEBVTT")

        return try {
            when {
                isAss -> parseAss(normalized)
                isVtt -> parseVtt(normalized)
                else -> parseSrt(normalized)
            }
        } catch (e: Throwable) {
            // Fallback: try SRT parser on failure
            try {
                parseSrt(normalized)
            } catch (ignored: Throwable) {
                emptyList()
            }
        }
    }

    /**
     * Parse SubRip (.srt) subtitle format.
     */
    private fun parseSrt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val blocks = content.split("\n\n")

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var timeLineIndex = -1
            var startMs = -1L
            var endMs = -1L

            for (i in lines.indices) {
                val line = lines[i]
                val matcher = SRT_TIME_REGEX.matcher(line)
                if (matcher.find()) {
                    timeLineIndex = i
                    startMs = parseTimecode(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4))
                    endMs = parseTimecode(matcher.group(5), matcher.group(6), matcher.group(7), matcher.group(8))
                    break
                }
            }

            if (timeLineIndex != -1 && startMs >= 0L && endMs > startMs && timeLineIndex < lines.size - 1) {
                val textLines = lines.subList(timeLineIndex + 1, lines.size)
                val fullText = textLines.joinToString("\n")
                val cleanText = cleanHtmlTags(fullText)
                if (cleanText.isNotBlank()) {
                    cues.add(SubtitleCue(startMs, endMs, cleanText, cleanText))
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    /**
     * Parse WebVTT (.vtt) subtitle format.
     */
    private fun parseVtt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val lines = content.lines().map { it.trim() }
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("NOTE") || line.startsWith("WEBVTT") || line.startsWith("STYLE") || line.isEmpty()) {
                i++
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
                val text = cleanHtmlTags(textBuilder.toString())
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
                    val rawFormat = line.substringAfter("Format:").trim()
                    formatFields = rawFormat.split(",").map { it.trim().lowercase() }
                    startIndex = formatFields.indexOf("start")
                    endIndex = formatFields.indexOf("end")
                    textIndex = formatFields.indexOf("text")
                } else if (line.startsWith("Dialogue:", ignoreCase = true)) {
                    val rawDialogue = line.substringAfter("Dialogue:").trim()
                    val expectedParts = if (formatFields.isNotEmpty()) formatFields.size else 10
                    val parts = rawDialogue.split(",", limit = expectedParts)

                    val startStr = if (startIndex >= 0 && startIndex < parts.size) parts[startIndex].trim() else parts.getOrNull(1)?.trim()
                    val endStr = if (endIndex >= 0 && endIndex < parts.size) parts[endIndex].trim() else parts.getOrNull(2)?.trim()
                    val textPart = if (textIndex >= 0 && textIndex < parts.size) parts[textIndex] else parts.lastOrNull() ?: ""

                    if (!startStr.isNullOrBlank() && !endStr.isNullOrBlank()) {
                        val startMs = parseAssTimecode(startStr)
                        val endMs = parseAssTimecode(endStr)
                        if (startMs >= 0L && endMs > startMs) {
                            val cleanText = cleanAssText(textPart)
                            if (cleanText.isNotBlank()) {
                                cues.add(SubtitleCue(startMs, endMs, cleanText, cleanText))
                            }
                        }
                    }
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTimecode(h: String, m: String, s: String, ms: String): Long {
        return try {
            val hours = h.toLong()
            val mins = m.toLong()
            val secs = s.toLong()
            val millis = ms.padEnd(3, '0').take(3).toLong()
            (hours * 3600000L) + (mins * 60000L) + (secs * 1000L) + millis
        } catch (e: Exception) {
            -1L
        }
    }

    private fun parseShortTimecode(m: String, s: String, ms: String): Long {
        return try {
            val mins = m.toLong()
            val secs = s.toLong()
            val millis = ms.padEnd(3, '0').take(3).toLong()
            (mins * 60000L) + (secs * 1000L) + millis
        } catch (e: Exception) {
            -1L
        }
    }

    private fun parseAssTimecode(timeStr: String): Long {
        return try {
            // ASS format: 0:00:00.00 (hours:mins:secs.centisecs)
            val parts = timeStr.split(":")
            if (parts.size >= 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val secParts = parts[2].split(".")
                val s = secParts[0].toLong()
                val cs = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                (h * 3600000L) + (m * 60000L) + (s * 1000L) + cs
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Clean any subtitle cue text from ASS/SSA script tags, WebVTT tags, and HTML spans.
     */
    fun cleanCueText(text: String): String {
        if (text.isBlank()) return ""
        // Strip null characters and unprintable control characters
        var cleaned = text.replace("\u0000", "")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
        // 1. Remove ASS drawing vector blocks {\p1}m...{\p0}
        cleaned = ASS_DRAWING_REGEX.matcher(cleaned).replaceAll("")
        // 2. Remove ASS override tags {\...} (e.g. {\pos(x,y)}, {\c&H...&}, {\fn...}, {\fs...})
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
        // 6. Decode standard XML/HTML entities
        cleaned = unescapeHtmlEntities(cleaned).trim()

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

    private fun cleanHtmlTags(text: String): String {
        return cleanCueText(text)
    }

    private fun cleanAssText(text: String): String {
        return cleanCueText(text)
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
            } catch (e: Exception) {
                // Fallback
            }
        }

        // 4. Validate UTF-8 compliance, fallback to Windows-1252 / ISO-8859-1 if invalid
        if (!isValidUtf8(bytes)) {
            try {
                return Charset.forName("windows-1252")
            } catch (e: Exception) {
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
