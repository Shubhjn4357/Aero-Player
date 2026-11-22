package com.emptysol.aeroplayer.torrent

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class MagnetLink(
    val rawUri: String,
    val infoHashHex: String,
    val infoHashBytes: ByteArray,
    val displayName: String,
    val trackers: List<String>,
    val exactLength: Long = 0L,
    val webSeeds: List<String> = emptyList(),
    val selectedIndices: Set<Int> = emptySet()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MagnetLink) return false
        return infoHashHex == other.infoHashHex
    }

    override fun hashCode(): Int = infoHashHex.hashCode()
}

/**
 * Parses standard BEP-9 magnet links with 40-char Hex or 32-char Base32 info-hashes.
 */
object MagnetParser {

    fun isMagnetUri(uri: String): Boolean = uri.trim().startsWith("magnet:?", ignoreCase = true)

    fun parse(uri: String): MagnetLink {
        val trimmed = uri.trim()
        require(isMagnetUri(trimmed)) { "Not a valid magnet URI" }
        val query = trimmed.substringAfter("?", "")
        require(query.isNotBlank()) { "Magnet URI contains no query parameters" }

        var infoHashHex: String? = null
        var infoHashBytes: ByteArray? = null
        var displayName = ""
        val trackers = linkedSetOf<String>()
        val webSeeds = linkedSetOf<String>()
        var exactLength = 0L
        val selectedIndices = linkedSetOf<Int>()

        query.split("&").forEach { pair ->
            if (pair.isBlank()) return@forEach
            val key = pair.substringBefore("=").lowercase()
            val rawVal = pair.substringAfter("=", "")
            val decodedVal = try {
                URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                rawVal
            }

            when (key) {
                "xt" -> {
                    val btih = when {
                        decodedVal.startsWith("urn:btih:", ignoreCase = true) ->
                            decodedVal.substringAfter(":btih:", "").trim()
                        decodedVal.startsWith("urn:btmh:1220", ignoreCase = true) ->
                            decodedVal.substringAfter("1220", "").take(40)
                        else -> ""
                    }
                    if (btih.isNotBlank()) {
                        val parsedBytes = decodeInfoHash(btih)
                        if (parsedBytes != null) {
                            infoHashBytes = parsedBytes
                            infoHashHex = BencodeCodec.toHex(parsedBytes)
                        }
                    }
                }
                "dn" -> {
                    if (decodedVal.isNotBlank()) displayName = decodedVal
                }
                "tr" -> {
                    if (decodedVal.isNotBlank()) trackers.add(decodedVal)
                }
                "ws" -> {
                    if (decodedVal.isNotBlank()) webSeeds.add(decodedVal)
                }
                "xl" -> {
                    exactLength = decodedVal.toLongOrNull() ?: exactLength
                }
                "so" -> {
                    decodedVal.split(",").forEach { token ->
                        if (token.contains("-")) {
                            val start = token.substringBefore("-").toIntOrNull()
                            val end = token.substringAfter("-").toIntOrNull()
                            if (start != null && end != null && start <= end) {
                                for (i in start..end) selectedIndices.add(i)
                            }
                        } else {
                            token.toIntOrNull()?.let { selectedIndices.add(it) }
                        }
                    }
                }
            }
        }

        val finalHex = infoHashHex ?: throw IllegalArgumentException("Magnet link missing valid btih hash")
        val finalBytes = infoHashBytes ?: BencodeCodec.fromHex(finalHex)
        val finalTitle = displayName.ifBlank { "Torrent ${finalHex.take(8).uppercase()}" }

        return MagnetLink(
            rawUri = trimmed,
            infoHashHex = finalHex,
            infoHashBytes = finalBytes,
            displayName = finalTitle,
            trackers = trackers.toList(),
            exactLength = exactLength,
            webSeeds = webSeeds.toList(),
            selectedIndices = selectedIndices
        )
    }

    private fun decodeInfoHash(raw: String): ByteArray? {
        val clean = raw.trim().trimEnd('=')
        if (clean.length == 40 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return BencodeCodec.fromHex(clean)
        }
        if (clean.length == 32) {
            return decodeBase32(clean)
        }
        return null
    }

    private fun decodeBase32(input: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val upper = input.uppercase().replace("=", "")
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>(20)
        for (c in upper) {
            val idx = alphabet.indexOf(c)
            if (idx < 0) return null
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        val arr = out.toByteArray()
        return if (arr.size == 20) arr else null
    }
}
