package com.emptysol.aeroplayer.torrent

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class TorrentFileEntry(
    val index: Int,
    val path: String,
    val length: Long,
    val priority: String = "NORMAL", // "HIGH", "NORMAL", "LOW", "SKIP"
    val downloadedBytes: Long = 0L
)

data class TorrentMetainfo(
    val infoHashHex: String,
    val infoHashBytes: ByteArray,
    val name: String,
    val totalLength: Long,
    val pieceLength: Long,
    val pieceHashes: List<ByteArray>,
    val files: List<TorrentFileEntry>,
    val trackers: List<String>,
    val comment: String = "",
    val createdBy: String = "",
    val isPrivate: Boolean = false,
    val rawInfoBytes: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TorrentMetainfo) return false
        return infoHashHex == other.infoHashHex
    }

    override fun hashCode(): Int = infoHashHex.hashCode()
}

/**
 * Pure Kotlin BEP-3 Bencode codec & .torrent metainfo parser.
 */
object BencodeCodec {

    data class DecodeResult(
        val value: Any?,
        val nextIndex: Int,
        val infoSlice: Pair<Int, Int>? = null
    )

    fun decode(bytes: ByteArray): Any? = decodeWithInfoSlice(bytes).first

    fun decodeWithInfoSlice(bytes: ByteArray): Pair<Any?, ByteArray?> {
        val result = parseNode(bytes, 0)
        val infoBytes = result.infoSlice?.let { (start, end) ->
            if (start >= 0 && end <= bytes.size && end > start) {
                bytes.copyOfRange(start, end)
            } else {
                null
            }
        }
        return result.value to infoBytes
    }

    private fun parseNode(bytes: ByteArray, index: Int): DecodeResult {
        if (index >= bytes.size) {
            throw IllegalArgumentException("Unexpected end of bencoded stream at $index")
        }
        return when (val ch = bytes[index].toInt().toChar()) {
            'i' -> {
                val endIdx = findChar(bytes, index + 1, 'e')
                    ?: throw IllegalArgumentException("Unterminated bencoded integer at $index")
                val numStr = String(bytes, index + 1, endIdx - (index + 1), StandardCharsets.US_ASCII)
                DecodeResult(numStr.toLong(), endIdx + 1)
            }
            'l' -> {
                val list = mutableListOf<Any?>()
                var cur = index + 1
                var foundInfoSlice: Pair<Int, Int>? = null
                while (cur < bytes.size && bytes[cur].toInt().toChar() != 'e') {
                    val elem = parseNode(bytes, cur)
                    list.add(elem.value)
                    if (foundInfoSlice == null && elem.infoSlice != null) {
                        foundInfoSlice = elem.infoSlice
                    }
                    cur = elem.nextIndex
                }
                if (cur >= bytes.size) throw IllegalArgumentException("Unterminated bencoded list at $index")
                DecodeResult(list, cur + 1, foundInfoSlice)
            }
            'd' -> {
                val map = LinkedHashMap<String, Any?>()
                var cur = index + 1
                var foundInfoSlice: Pair<Int, Int>? = null
                while (cur < bytes.size && bytes[cur].toInt().toChar() != 'e') {
                    val keyNode = parseNode(bytes, cur)
                    val keyBytes = keyNode.value as? ByteArray
                        ?: throw IllegalArgumentException("Dictionary key is not byte string at $cur")
                    val keyStr = String(keyBytes, StandardCharsets.UTF_8)
                    val valueStart = keyNode.nextIndex
                    val valNode = parseNode(bytes, valueStart)
                    map[keyStr] = valNode.value
                    if (keyStr == "info" && valNode.value is Map<*, *>) {
                        foundInfoSlice = valueStart to valNode.nextIndex
                    } else if (foundInfoSlice == null && valNode.infoSlice != null) {
                        foundInfoSlice = valNode.infoSlice
                    }
                    cur = valNode.nextIndex
                }
                if (cur >= bytes.size) throw IllegalArgumentException("Unterminated bencoded dictionary at $index")
                DecodeResult(map, cur + 1, foundInfoSlice)
            }
            in '0'..'9' -> {
                val colonIdx = findChar(bytes, index, ':')
                    ?: throw IllegalArgumentException("Missing ':' in bencoded byte string at $index")
                val lenStr = String(bytes, index, colonIdx - index, StandardCharsets.US_ASCII)
                val length = lenStr.toInt()
                val start = colonIdx + 1
                val end = start + length
                if (length < 0 || end > bytes.size) {
                    throw IllegalArgumentException("Invalid byte string length $length at $index")
                }
                DecodeResult(bytes.copyOfRange(start, end), end)
            }
            else -> throw IllegalArgumentException("Invalid bencode token '$ch' at $index")
        }
    }

    private fun findChar(bytes: ByteArray, start: Int, target: Char): Int? {
        val t = target.code.toByte()
        for (i in start until bytes.size) {
            if (bytes[i] == t) return i
        }
        return null
    }

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        writeEncoded(value, out)
        return out.toByteArray()
    }

    private fun writeEncoded(value: Any?, out: ByteArrayOutputStream) {
        when (value) {
            is ByteArray -> {
                out.write(value.size.toString().toByteArray(StandardCharsets.US_ASCII))
                out.write(':'.code)
                out.write(value)
            }
            is String -> {
                val raw = value.toByteArray(StandardCharsets.UTF_8)
                out.write(raw.size.toString().toByteArray(StandardCharsets.US_ASCII))
                out.write(':'.code)
                out.write(raw)
            }
            is Int, is Long -> {
                out.write('i'.code)
                out.write(value.toString().toByteArray(StandardCharsets.US_ASCII))
                out.write('e'.code)
            }
            is List<*> -> {
                out.write('l'.code)
                for (item in value) writeEncoded(item, out)
                out.write('e'.code)
            }
            is Map<*, *> -> {
                out.write('d'.code)
                val sortedKeys = value.keys.map { it.toString() }.sorted()
                for (k in sortedKeys) {
                    writeEncoded(k, out)
                    writeEncoded(value[k], out)
                }
                out.write('e'.code)
            }
            else -> throw IllegalArgumentException("Unsupported bencode type: ${value?.javaClass}")
        }
    }

    fun sha1(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(bytes)

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        require(clean.length % 2 == 0) { "Invalid hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun ByteArray?.asUtf8(): String = if (this == null) "" else String(this, StandardCharsets.UTF_8)
}

object TorrentMetainfoParser {

    @Suppress("UNCHECKED_CAST")
    fun parse(torrentBytes: ByteArray): TorrentMetainfo {
        val (rootObj, rawInfoSlice) = BencodeCodec.decodeWithInfoSlice(torrentBytes)
        val root = rootObj as? Map<String, Any?>
            ?: throw IllegalArgumentException("Torrent root is not a bencoded dictionary")

        val infoMap = root["info"] as? Map<String, Any?>
            ?: throw IllegalArgumentException("Torrent missing 'info' dictionary")

        val infoBytes = rawInfoSlice ?: BencodeCodec.encode(infoMap)
        val infoHashBytes = BencodeCodec.sha1(infoBytes)
        val infoHashHex = BencodeCodec.toHex(infoHashBytes)

        return buildMetainfoFromInfoMap(
            infoHashHex = infoHashHex,
            infoHashBytes = infoHashBytes,
            infoMap = infoMap,
            rawInfoBytes = infoBytes,
            root = root
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun parseInfoDictionary(infoHashBytes: ByteArray, rawInfoBytes: ByteArray): TorrentMetainfo {
        val infoMap = BencodeCodec.decode(rawInfoBytes) as? Map<String, Any?>
            ?: throw IllegalArgumentException("Metadata is not a bencoded dictionary")
        return buildMetainfoFromInfoMap(
            infoHashHex = BencodeCodec.toHex(infoHashBytes),
            infoHashBytes = infoHashBytes,
            infoMap = infoMap,
            rawInfoBytes = rawInfoBytes,
            root = emptyMap()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildMetainfoFromInfoMap(
        infoHashHex: String,
        infoHashBytes: ByteArray,
        infoMap: Map<String, Any?>,
        rawInfoBytes: ByteArray,
        root: Map<String, Any?>
    ): TorrentMetainfo {
        val name = (infoMap["name"] as? ByteArray)?.toString(StandardCharsets.UTF_8)
            ?.ifBlank { infoHashHex } ?: infoHashHex
        val pieceLength = (infoMap["piece length"] as? Long) ?: 262144L
        val rawPieces = (infoMap["pieces"] as? ByteArray) ?: ByteArray(0)
        val pieceHashes = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 20 <= rawPieces.size) {
            pieceHashes.add(rawPieces.copyOfRange(offset, offset + 20))
            offset += 20
        }

        val filesList = mutableListOf<TorrentFileEntry>()
        val multiFiles = infoMap["files"] as? List<Map<String, Any?>>
        val totalLength: Long
        if (multiFiles != null && multiFiles.isNotEmpty()) {
            var sum = 0L
            multiFiles.forEachIndexed { idx, fileMap ->
                val len = (fileMap["length"] as? Long) ?: 0L
                val pathParts = (fileMap["path"] as? List<ByteArray>)
                    ?.map { String(it, StandardCharsets.UTF_8) }
                    ?.filter { it.isNotBlank() }
                    ?: listOf("file_$idx")
                val relPath = pathParts.joinToString("/")
                filesList.add(
                    TorrentFileEntry(
                        index = idx,
                        path = relPath,
                        length = len,
                        priority = "NORMAL"
                    )
                )
                sum += len
            }
            totalLength = sum
        } else {
            val singleLen = (infoMap["length"] as? Long) ?: 0L
            filesList.add(
                TorrentFileEntry(
                    index = 0,
                    path = name,
                    length = singleLen,
                    priority = "NORMAL"
                )
            )
            totalLength = singleLen
        }

        val trackers = linkedSetOf<String>()
        (root["announce"] as? ByteArray)?.toString(StandardCharsets.UTF_8)?.trim()?.let {
            if (it.isNotBlank()) trackers.add(it)
        }
        val announceList = root["announce-list"] as? List<*>
        announceList?.forEach { tier ->
            (tier as? List<*>)?.forEach { item ->
                (item as? ByteArray)?.toString(StandardCharsets.UTF_8)?.trim()?.let {
                    if (it.isNotBlank()) trackers.add(it)
                }
            }
        }

        val comment = (root["comment"] as? ByteArray)?.toString(StandardCharsets.UTF_8) ?: ""
        val createdBy = (root["created by"] as? ByteArray)?.toString(StandardCharsets.UTF_8) ?: ""
        val isPrivate = ((infoMap["private"] as? Long) ?: 0L) == 1L

        return TorrentMetainfo(
            infoHashHex = infoHashHex,
            infoHashBytes = infoHashBytes,
            name = name,
            totalLength = totalLength,
            pieceLength = pieceLength,
            pieceHashes = pieceHashes,
            files = filesList,
            trackers = trackers.toList(),
            comment = comment,
            createdBy = createdBy,
            isPrivate = isPrivate,
            rawInfoBytes = rawInfoBytes
        )
    }
}
