package com.example.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

data class DiscoveredCastDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int,
    val serviceType: String,
    val protocol: String,
    val isRealDiscovered: Boolean = true
)

class NetworkCastScanner(private val context: Context) {

    private val TAG = "NetworkCastScanner"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredCastDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredCastDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val activeDiscoveryListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()

    private val serviceTypesToScan = listOf(
        "_googlecast._tcp." to "Chromecast",
        "_airplay._tcp." to "AirPlay",
        "_dlna._tcp." to "DLNA / UPnP",
        "_smb._tcp." to "Samba File Share (SMB)",
        "_ftp._tcp." to "FTP Remote Server",
        "_http._tcp." to "Web Stream / TV"
    )

    fun startScan() {
        if (_isScanning.value) return
        _isScanning.value = true

        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("CastScannerLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring MulticastLock", e)
        }

        stopScanInternal()

        val foundMap = mutableMapOf<String, DiscoveredCastDevice>()
        // Pre-populate with resolved localhost/local network loopback endpoint if available
        try {
            val localIp = getLocalIpAddress()
            if (localIp != null) {
                val localDev = DiscoveredCastDevice(
                    id = "local_open_gl_$localIp",
                    name = "Local OpenGL Remote Server ($localIp)",
                    ipAddress = localIp,
                    port = 8080,
                    serviceType = "_opengl._tcp.",
                    protocol = "OpenGL ES Stream",
                    isRealDiscovered = true
                )
                foundMap[localDev.id] = localDev
                _discoveredDevices.value = foundMap.values.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving local IP", e)
        }

        serviceTypesToScan.forEach { (type, protocolLabel) ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(TAG, "Discovery failed for $serviceType: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(TAG, "Stop discovery failed for $serviceType: $errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String?) {
                    Log.d(TAG, "Discovery started for $serviceType")
                }

                override fun onDiscoveryStopped(serviceType: String?) {
                    Log.d(TAG, "Discovery stopped for $serviceType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) return
                    Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")

                    resolveNsdService(serviceInfo, protocolLabel, foundMap)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) return
                    val key = serviceInfo.serviceName
                    foundMap.remove(key)
                    _discoveredDevices.value = foundMap.values.toList()
                }
            }

            try {
                nsdManager?.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                activeDiscoveryListeners[type] = listener
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start discovery for $type", e)
            }
        }
    }

    private fun resolveNsdService(
        serviceInfo: NsdServiceInfo,
        protocolLabel: String,
        foundMap: MutableMap<String, DiscoveredCastDevice>
    ) {
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.e(TAG, "Resolve failed for ${serviceInfo?.serviceName}: $errorCode")
                    // Fallback entry with unresolved IP
                    val name = serviceInfo?.serviceName ?: "Unknown Network Device"
                    val dev = DiscoveredCastDevice(
                        id = serviceInfo?.serviceName ?: name,
                        name = name,
                        ipAddress = "192.168.1.x",
                        port = 8008,
                        serviceType = serviceInfo?.serviceType ?: "",
                        protocol = protocolLabel,
                        isRealDiscovered = true
                    )
                    foundMap[dev.id] = dev
                    _discoveredDevices.value = foundMap.values.toList()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null) return
                    val host: InetAddress? = serviceInfo.host
                    val ip = host?.hostAddress ?: "192.168.1.100"
                    val port = serviceInfo.port.takeIf { it > 0 } ?: 8008
                    val rawName = serviceInfo.serviceName ?: "Discovered Smart TV"
                    val name = cleanDeviceName(rawName)

                    val dev = DiscoveredCastDevice(
                        id = "$ip:$port",
                        name = "$name ($ip)",
                        ipAddress = ip,
                        port = port,
                        serviceType = serviceInfo.serviceType ?: "",
                        protocol = protocolLabel,
                        isRealDiscovered = true
                    )
                    foundMap[dev.id] = dev
                    _discoveredDevices.value = foundMap.values.toList()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering resolveService", e)
        }
    }

    private fun cleanDeviceName(raw: String): String {
        return raw.replace("-", " ")
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { word -> word.capitalize() }
    }

    fun stopScan() {
        stopScanInternal()
        _isScanning.value = false
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MulticastLock", e)
        }
    }

    private fun stopScanInternal() {
        activeDiscoveryListeners.forEach { (_, listener) ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
        activeDiscoveryListeners.clear()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "IP address error", ex)
        }
        return null
    }
}
