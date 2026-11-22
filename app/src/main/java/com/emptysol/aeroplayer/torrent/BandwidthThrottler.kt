package com.emptysol.aeroplayer.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Token-bucket bandwidth rate limiter for global and per-torrent download/upload caps (KB/s).
 */
class BandwidthThrottler(
    initialKbps: Int = 0
) {
    @Volatile
    private var bytesPerSec: Long = kbpsToBps(initialKbps)

    private var availableTokens: Double = 0.0
    private var lastRefillNs: Long = System.nanoTime()
    private val lock = Any()

    fun setLimitKbps(kbps: Int) {
        synchronized(lock) {
            bytesPerSec = kbpsToBps(kbps)
            if (bytesPerSec > 0) {
                availableTokens = min(availableTokens, bytesPerSec.toDouble())
            }
        }
    }

    fun getLimitKbps(): Int = if (bytesPerSec <= 0L) 0 else (bytesPerSec / 1024L).toInt()

    suspend fun acquire(requestedBytes: Int) {
        if (requestedBytes <= 0) return
        val limit = bytesPerSec
        if (limit <= 0L) return // unlimited

        var sleepMs = 0L
        synchronized(lock) {
            val now = System.nanoTime()
            val elapsedSec = (now - lastRefillNs).coerceAtLeast(0L) / 1_000_000_000.0
            lastRefillNs = now
            val maxBurst = maxOf(limit.toDouble(), requestedBytes.toDouble() * 2.0)
            availableTokens = min(maxBurst, availableTokens + elapsedSec * limit)
            availableTokens -= requestedBytes.toDouble()
            if (availableTokens < 0.0) {
                val deficit = -availableTokens
                sleepMs = ((deficit / limit) * 1000.0).toLong().coerceIn(5L, 1500L)
            }
        }
        if (sleepMs > 0L) {
            delay(sleepMs)
        }
    }

    private fun kbpsToBps(kbps: Int): Long = if (kbps <= 0) 0L else kbps.toLong() * 1024L
}

data class NetworkConditionRecommendation(
    val transportLabel: String,
    val isMetered: Boolean,
    val linkDownstreamKbps: Int,
    val linkUpstreamKbps: Int,
    val recommendedDownloadCapKbps: Int, // 0 = full speed
    val recommendedUploadCapKbps: Int
)

/**
 * Inspects real ConnectivityManager active network link capabilities to dynamically scale
 * upload/download budgets and protect connection responsiveness.
 */
object AutoBandwidthOptimizer {

    fun evaluate(context: Context): NetworkConditionRecommendation {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetworkConditionRecommendation("Offline", false, 0, 0, 512, 128)
            val network = cm.activeNetwork
                ?: return NetworkConditionRecommendation("Offline", false, 0, 0, 0, 0)
            val caps = cm.getNetworkCapabilities(network)
                ?: return NetworkConditionRecommendation("Unknown", false, 0, 0, 1024, 256)

            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            val downKbps = caps.linkDownstreamBandwidthKbps.coerceAtLeast(0)
            val upKbps = caps.linkUpstreamBandwidthKbps.coerceAtLeast(0)

            val transportLabel = buildString {
                when {
                    isEthernet -> append("Ethernet")
                    isWifi -> append("Wi-Fi")
                    isCellular -> append("Cellular")
                    else -> append("Network")
                }
                if (isVpn) append(" + VPN")
                if (isMetered) append(" (Metered)")
            }

            // Convert link kbps (bits/s) to KB/s caps leaving ~20% headroom for foreground streaming/UI
            val recDownKBps = when {
                isMetered && isCellular -> minOf(2048, ((downKbps / 8.0) * 0.65).toInt().coerceAtLeast(512))
                downKbps > 0 -> ((downKbps / 8.0) * 0.85).toInt().coerceAtLeast(1024)
                else -> 0 // uncapped
            }
            val recUpKBps = when {
                isMetered && isCellular -> minOf(256, ((upKbps / 8.0) * 0.35).toInt().coerceIn(64, 256))
                upKbps > 0 -> ((upKbps / 8.0) * 0.60).toInt().coerceIn(128, 4096)
                else -> 512
            }

            NetworkConditionRecommendation(
                transportLabel = transportLabel,
                isMetered = isMetered,
                linkDownstreamKbps = downKbps,
                linkUpstreamKbps = upKbps,
                recommendedDownloadCapKbps = recDownKBps,
                recommendedUploadCapKbps = recUpKBps
            )
        } catch (_: Throwable) {
            NetworkConditionRecommendation("Network", false, 0, 0, 0, 512)
        }
    }
}
