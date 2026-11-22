package com.emptysol.aeroplayer.torrent

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Parses PeerGuardian (.dat / .p2p), CIDR blocks (e.g. 192.168.1.0/24), and IP ranges
 * (e.g. 1.2.3.0-1.2.3.255) to block unwanted peers before TCP socket handshake.
 */
class IpFilterManager(rulesText: String = "") {

    data class IpRange(
        val startIp: Long,
        val endIp: Long,
        val label: String = ""
    )

    @Volatile
    private var ranges: List<IpRange> = emptyList()

    init {
        loadRules(rulesText)
    }

    fun loadRules(rulesText: String): Int {
        if (rulesText.isBlank()) {
            ranges = emptyList()
            return 0
        }
        val parsed = ArrayList<IpRange>()
        rulesText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) return@forEach

            // Support PeerGuardian "Description:start-end" or "start - end" or CIDR "a.b.c.d/prefix" or single IP
            val rangePart = if (line.contains(":") && line.substringAfterLast(":").contains("-")) {
                line.substringAfterLast(":").trim()
            } else {
                line.trim()
            }
            val label = if (line.contains(":") && line.substringAfterLast(":").contains("-")) {
                line.substringBeforeLast(":").trim()
            } else {
                ""
            }

            when {
                rangePart.contains("/") -> {
                    val ipPart = rangePart.substringBefore("/").trim()
                    val prefix = rangePart.substringAfter("/").trim().toIntOrNull() ?: return@forEach
                    val base = ipv4ToLong(ipPart) ?: return@forEach
                    if (prefix in 0..32) {
                        val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
                        val start = base and mask
                        val end = start or (mask.inv() and 0xFFFFFFFFL)
                        parsed.add(IpRange(start, end, label))
                    }
                }
                rangePart.contains("-") -> {
                    val startIp = ipv4ToLong(rangePart.substringBefore("-").trim()) ?: return@forEach
                    val endIp = ipv4ToLong(rangePart.substringAfter("-").trim()) ?: return@forEach
                    if (startIp <= endIp) {
                        parsed.add(IpRange(startIp, endIp, label))
                    } else {
                        parsed.add(IpRange(endIp, startIp, label))
                    }
                }
                else -> {
                    val single = ipv4ToLong(rangePart) ?: return@forEach
                    parsed.add(IpRange(single, single, label))
                }
            }
        }
        ranges = mergeRanges(parsed)
        return ranges.size
    }

    fun ruleCount(): Int = ranges.size

    fun isBlocked(ipAddress: String): Boolean {
        val current = ranges
        if (current.isEmpty()) return false
        val target = ipv4ToLong(ipAddress) ?: return false

        // Binary search on merged, non-overlapping sorted ranges
        var low = 0
        var high = current.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val r = current[mid]
            when {
                target < r.startIp -> high = mid - 1
                target > r.endIp -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    private fun mergeRanges(input: List<IpRange>): List<IpRange> {
        if (input.size <= 1) return input
        val sorted = input.sortedBy { it.startIp }
        val merged = ArrayList<IpRange>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startIp <= cur.endIp + 1L) {
                cur = cur.copy(endIp = maxOf(cur.endIp, next.endIp))
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        return merged
    }

    companion object {
        fun ipv4ToLong(ip: String): Long? {
            val clean = ip.trim().substringBefore("%")
            val parts = clean.split(".")
            if (parts.size == 4) {
                var acc = 0L
                for (p in parts) {
                    val v = p.toIntOrNull() ?: return null
                    if (v !in 0..255) return null
                    acc = (acc shl 8) or v.toLong()
                }
                return acc and 0xFFFFFFFFL
            }
            return try {
                val addr = InetAddress.getByName(clean)
                if (addr is Inet4Address) {
                    val b = addr.address
                    ((b[0].toLong() and 0xFF) shl 24) or
                        ((b[1].toLong() and 0xFF) shl 16) or
                        ((b[2].toLong() and 0xFF) shl 8) or
                        (b[3].toLong() and 0xFF)
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
