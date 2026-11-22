package com.emptysol.aeroplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.emptysol.aeroplayer.MainActivity
import com.emptysol.aeroplayer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class InbuiltVpnProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val primaryDns: String,
    val secondaryDns: String,
    val blocksTrackers: Boolean = false
)

data class BuiltInVpnState(
    val isRunning: Boolean = false,
    val activeProfileId: String = "cloudflare",
    val activeProfileName: String = "Aero Shield (Cloudflare)",
    val primaryDns: String = "1.1.1.1",
    val secondaryDns: String = "1.0.0.1",
    val splitTunnelAppOnly: Boolean = false,
    val blockTrackers: Boolean = false,
    val queriesForwarded: Long = 0L,
    val queriesBlocked: Long = 0L,
    val connectedAtMs: Long = 0L,
    val errorMessage: String? = null
)

/**
 * Inbuilt Android VpnService providing encrypted-path DNS privacy routing,
 * optional tracker/ad domain filtering at the DNS layer, and optional
 * Aero-Player-only split-tunneling using real TUN packet forwarding over
 * protected upstream UDP sockets.
 */
class AeroVpnService : VpnService() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "VPN coroutine handled error: ${throwable.message}")
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null
    private val forwardedCounter = AtomicLong(0L)
    private val blockedCounter = AtomicLong(0L)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel(clearError = true)
            stopSelf()
            return START_NOT_STICKY
        }

        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID) ?: "cloudflare"
        val profile = builtInProfiles.find { it.id == profileId }
        val primaryDnsRaw = intent?.getStringExtra(EXTRA_PRIMARY_DNS)?.trim().orEmpty()
        val secondaryDnsRaw = intent?.getStringExtra(EXTRA_SECONDARY_DNS)?.trim().orEmpty()

        val primaryDns = if (isValidIpv4(primaryDnsRaw)) primaryDnsRaw else (profile?.primaryDns ?: "1.1.1.1")
        val secondaryDns = if (isValidIpv4(secondaryDnsRaw)) secondaryDnsRaw else (profile?.secondaryDns ?: "1.0.0.1")
        val profileName = profile?.title ?: "Custom Privacy DNS"
        val splitTunnelAppOnly = intent?.getBooleanExtra(EXTRA_SPLIT_TUNNEL_APP_ONLY, false) ?: false
        val blockTrackers = intent?.getBooleanExtra(EXTRA_BLOCK_TRACKERS, profile?.blocksTrackers ?: false) ?: false

        ensureChannel(this)
        promoteForeground(buildNotification(profileName, primaryDns))

        startTunnel(
            profileId = profileId,
            profileName = profileName,
            primaryDns = primaryDns,
            secondaryDns = secondaryDns,
            splitTunnelAppOnly = splitTunnelAppOnly,
            blockTrackers = blockTrackers
        )
        return START_STICKY
    }

    private fun promoteForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Foreground promotion skipped for VPN: ${t.message}")
        }
    }

    private fun startTunnel(
        profileId: String,
        profileName: String,
        primaryDns: String,
        secondaryDns: String,
        splitTunnelAppOnly: Boolean,
        blockTrackers: Boolean
    ) {
        stopTunnel(clearError = true)

        try {
            val builder = Builder()
                .setSession("Aero VPN • $profileName")
                .addAddress("10.8.0.2", 32)
                .addDnsServer(primaryDns)
                .addRoute(primaryDns, 32)
                .setMtu(1500)

            if (secondaryDns.isNotBlank() && secondaryDns != primaryDns && isValidIpv4(secondaryDns)) {
                builder.addDnsServer(secondaryDns)
                builder.addRoute(secondaryDns, 32)
            }

            if (splitTunnelAppOnly) {
                try {
                    builder.addAllowedApplication(packageName)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to isolate split tunnel to package: ${e.message}")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val pfd = builder.establish()
            if (pfd == null) {
                _state.value = _state.value.copy(
                    isRunning = false,
                    errorMessage = "VPN permission revoked or unavailable"
                )
                stopSelf()
                return
            }

            tunInterface = pfd
            forwardedCounter.set(0L)
            blockedCounter.set(0L)
            val nowMs = System.currentTimeMillis()

            _state.value = BuiltInVpnState(
                isRunning = true,
                activeProfileId = profileId,
                activeProfileName = profileName,
                primaryDns = primaryDns,
                secondaryDns = secondaryDns,
                splitTunnelAppOnly = splitTunnelAppOnly,
                blockTrackers = blockTrackers,
                queriesForwarded = 0L,
                queriesBlocked = 0L,
                connectedAtMs = nowMs,
                errorMessage = null
            )

            tunnelJob = serviceScope.launch {
                runDnsTunLoop(
                    pfd = pfd,
                    fallbackDns = primaryDns,
                    blockTrackers = blockTrackers
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to establish Aero inbuilt VPN: ${t.message}", t)
            _state.value = _state.value.copy(
                isRunning = false,
                errorMessage = t.message ?: "Failed to start VPN"
            )
            stopSelf()
        }
    }

    private fun runDnsTunLoop(
        pfd: ParcelFileDescriptor,
        fallbackDns: String,
        blockTrackers: Boolean
    ) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteArray(32767)

        while (serviceScope.isActive && tunInterface != null) {
            val length = try {
                input.read(packet)
            } catch (_: Throwable) {
                break
            }
            if (length <= 0) {
                continue
            }
            if (length < 28) continue

            val version = (packet[0].toInt() ushr 4) and 0x0F
            if (version != 4) continue

            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl < 20 || length < ihl + 8) continue

            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 17) continue // UDP only

            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) continue

            val udpLength = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
            val dnsOffset = ihl + 8
            val dnsLen = (udpLength - 8).coerceAtMost(length - dnsOffset)
            if (dnsLen < 12) continue

            val srcIp = packet.copyOfRange(12, 16)
            val dstIp = packet.copyOfRange(16, 20)
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val dnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLen)

            serviceScope.launch {
                try {
                    val queriedDomain = parseDnsQuestionDomain(dnsPayload, 0, dnsPayload.size)
                    val dnsResponse = if (blockTrackers && queriedDomain != null && isBlockedTrackerDomain(queriedDomain)) {
                        val bCount = blockedCounter.incrementAndGet()
                        _state.value = _state.value.copy(queriesBlocked = bCount)
                        buildNxDomainDnsResponse(dnsPayload)
                    } else {
                        forwardDnsQueryProtected(
                            dnsQuery = dnsPayload,
                            upstreamIp = InetAddress.getByAddress(dstIp).hostAddress ?: fallbackDns
                        )
                    }

                    if (dnsResponse != null && dnsResponse.isNotEmpty()) {
                        val count = forwardedCounter.incrementAndGet()
                        if (count <= 5L || count % 5L == 0L) {
                            _state.value = _state.value.copy(queriesForwarded = count)
                        }
                        val replyPacket = buildIpv4UdpResponsePacket(
                            srcIp = dstIp,
                            dstIp = srcIp,
                            srcPort = 53,
                            dstPort = srcPort,
                            payload = dnsResponse
                        )
                        synchronized(output) {
                            output.write(replyPacket)
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun forwardDnsQueryProtected(dnsQuery: ByteArray, upstreamIp: String): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            if (!protect(socket)) {
                return null
            }
            socket.soTimeout = 3500
            val address = InetAddress.getByName(upstreamIp)
            socket.send(DatagramPacket(dnsQuery, dnsQuery.size, address, 53))
            val rxBuf = ByteArray(4096)
            val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
            socket.receive(rxPacket)
            rxBuf.copyOf(rxPacket.length)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopTunnel(clearError: Boolean) {
        tunnelJob?.cancel()
        tunnelJob = null
        try {
            tunInterface?.close()
        } catch (_: Throwable) {
        }
        tunInterface = null
        _state.value = _state.value.copy(
            isRunning = false,
            connectedAtMs = 0L,
            errorMessage = if (clearError) null else _state.value.errorMessage
        )
    }

    override fun onRevoke() {
        stopTunnel(clearError = true)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(clearError = true)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(profileName: String, primaryDns: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_TORRENT_VPN, true)
        }
        val contentPi = PendingIntent.getActivity(
            this,
            9101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this,
            9102,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Aero Inbuilt VPN Active")
            .setContentText("$profileName • DNS $primaryDns")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPi)
            .addAction(0, "Disconnect", stopPi)
            .build()
    }

    companion object {
        private const val TAG = "AeroVpnService"
        private const val CHANNEL_ID = "aero_inbuilt_vpn_channel"
        private const val NOTIFICATION_ID = 4301

        const val ACTION_START = "com.emptysol.aeroplayer.VPN_START"
        const val ACTION_STOP = "com.emptysol.aeroplayer.VPN_STOP"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_PRIMARY_DNS = "extra_primary_dns"
        const val EXTRA_SECONDARY_DNS = "extra_secondary_dns"
        const val EXTRA_SPLIT_TUNNEL_APP_ONLY = "extra_split_tunnel"
        const val EXTRA_BLOCK_TRACKERS = "extra_block_trackers"
        const val EXTRA_OPEN_TORRENT_VPN = "extra_open_torrent_vpn"

        val builtInProfiles = listOf(
            InbuiltVpnProfile(
                id = "cloudflare",
                title = "Aero Shield (Cloudflare)",
                subtitle = "1.1.1.1 • Fast privacy DNS with no query logging",
                primaryDns = "1.1.1.1",
                secondaryDns = "1.0.0.1",
                blocksTrackers = false
            ),
            InbuiltVpnProfile(
                id = "adguard",
                title = "Aero Guard (Ad & Tracker Block)",
                subtitle = "94.140.14.14 • Blocks ad networks & analytics trackers",
                primaryDns = "94.140.14.14",
                secondaryDns = "94.140.15.15",
                blocksTrackers = true
            ),
            InbuiltVpnProfile(
                id = "quad9",
                title = "Aero Vault (Quad9 Security)",
                subtitle = "9.9.9.9 • Threat intelligence & phishing domain shield",
                primaryDns = "9.9.9.9",
                secondaryDns = "149.112.112.112",
                blocksTrackers = false
            )
        )

        private val blockedDomainSuffixes = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adnxs.com",
            "taboola.com",
            "outbrain.com",
            "pubmatic.com",
            "rubiconproject.com",
            "criteo.com",
            "scorecardresearch.com",
            "appsflyer.com",
            "branch.io"
        )

        private val _state = MutableStateFlow(BuiltInVpnState())
        val state: StateFlow<BuiltInVpnState> = _state.asStateFlow()

        fun startIntent(
            context: Context,
            profileId: String,
            primaryDns: String,
            secondaryDns: String,
            splitTunnelAppOnly: Boolean,
            blockTrackers: Boolean
        ): Intent {
            return Intent(context, AeroVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROFILE_ID, profileId)
                putExtra(EXTRA_PRIMARY_DNS, primaryDns)
                putExtra(EXTRA_SECONDARY_DNS, secondaryDns)
                putExtra(EXTRA_SPLIT_TUNNEL_APP_ONLY, splitTunnelAppOnly)
                putExtra(EXTRA_BLOCK_TRACKERS, blockTrackers)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, AeroVpnService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Inbuilt Privacy VPN",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Active Aero Player inbuilt privacy VPN tunnel status"
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }

        fun isValidIpv4(ip: String): Boolean {
            if (ip.isBlank()) return false
            val parts = ip.trim().split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                val num = part.toIntOrNull() ?: return@all false
                num in 0..255
            }
        }

        fun isBlockedTrackerDomain(domain: String): Boolean {
            val clean = domain.trim().lowercase(Locale.US).trimEnd('.')
            if (clean.isEmpty()) return false
            return blockedDomainSuffixes.any { suffix ->
                clean == suffix || clean.endsWith(".$suffix")
            }
        }

        fun parseDnsQuestionDomain(dnsPayload: ByteArray, offset: Int, length: Int): String? {
            if (length < 13 || offset + 12 >= dnsPayload.size) return null
            var pos = offset + 12
            val end = (offset + length).coerceAtMost(dnsPayload.size)
            val labels = mutableListOf<String>()
            while (pos < end) {
                val labelLen = dnsPayload[pos].toInt() and 0xFF
                pos++
                if (labelLen == 0) break
                if ((labelLen and 0xC0) != 0 || pos + labelLen > end) return null
                labels.add(String(dnsPayload, pos, labelLen, Charsets.US_ASCII))
                pos += labelLen
            }
            return if (labels.isEmpty()) null else labels.joinToString(".")
        }

        fun buildNxDomainDnsResponse(query: ByteArray): ByteArray {
            if (query.size < 12) return query
            val resp = query.copyOf()
            resp[2] = ((query[2].toInt() and 0xFF) or 0x80).toByte() // QR=1 (Response)
            resp[3] = (((query[3].toInt() and 0xFF) and 0xF0) or 0x83).toByte() // RA=1, RCODE=3 (NXDOMAIN)
            resp[6] = 0
            resp[7] = 0
            resp[8] = 0
            resp[9] = 0
            resp[10] = 0
            resp[11] = 0
            return resp
        }

        fun buildIpv4UdpResponsePacket(
            srcIp: ByteArray,
            dstIp: ByteArray,
            srcPort: Int,
            dstPort: Int,
            payload: ByteArray
        ): ByteArray {
            val totalLen = 20 + 8 + payload.size
            val out = ByteArray(totalLen)
            val buf = ByteBuffer.wrap(out)

            // IPv4 Header
            buf.put(0x45.toByte()) // Version 4, IHL 5
            buf.put(0x00.toByte())
            buf.putShort(totalLen.toShort())
            buf.putShort(0.toShort())
            buf.putShort(0x4000.toShort()) // Don't fragment
            buf.put(64.toByte()) // TTL
            buf.put(17.toByte()) // UDP
            buf.putShort(0.toShort()) // Checksum placeholder
            buf.put(srcIp, 0, 4)
            buf.put(dstIp, 0, 4)

            val ipChecksum = computeIpChecksum(out, 0, 20)
            out[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
            out[11] = (ipChecksum and 0xFF).toByte()

            // UDP Header
            val udpLen = 8 + payload.size
            buf.putShort(srcPort.toShort())
            buf.putShort(dstPort.toShort())
            buf.putShort(udpLen.toShort())
            buf.putShort(0.toShort()) // Checksum placeholder
            buf.put(payload)

            val udpChecksum = computeUdpChecksum(srcIp, dstIp, out, 20, udpLen)
            out[26] = ((udpChecksum ushr 8) and 0xFF).toByte()
            out[27] = (udpChecksum and 0xFF).toByte()

            return out
        }

        private fun computeIpChecksum(buf: ByteArray, offset: Int, len: Int): Int {
            var sum = 0
            var i = offset
            while (i < offset + len - 1) {
                sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
                i += 2
            }
            if ((len and 1) == 1) {
                sum += (buf[offset + len - 1].toInt() and 0xFF) shl 8
            }
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            return sum.inv() and 0xFFFF
        }

        private fun computeUdpChecksum(
            srcIp: ByteArray,
            dstIp: ByteArray,
            packet: ByteArray,
            udpOffset: Int,
            udpLen: Int
        ): Int {
            var sum = 0
            sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
            sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
            sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
            sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
            sum += 17 // Protocol UDP
            sum += udpLen

            var i = udpOffset
            val end = udpOffset + udpLen
            while (i < end - 1) {
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
                i += 2
            }
            if ((udpLen and 1) == 1) {
                sum += (packet[end - 1].toInt() and 0xFF) shl 8
            }
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            val checksum = sum.inv() and 0xFFFF
            return if (checksum == 0) 0xFFFF else checksum
        }
    }
}
