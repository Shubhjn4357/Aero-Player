package com.emptysol.aeroplayer.torrent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.emptysol.aeroplayer.service.AeroVpnService
import org.json.JSONArray

data class InstalledVpnApp(
    val packageName: String,
    val displayName: String,
    val isInstalled: Boolean,
    val playStoreUrl: String = "https://play.google.com/store/apps/details?id=$packageName",
    val isCustomAdded: Boolean = false
)

data class AeroVpnConfig(
    val selectedMode: String = "INBUILT", // "INBUILT" or "ADDON"
    val inbuiltProfileId: String = "cloudflare",
    val customPrimaryDns: String = "1.1.1.1",
    val customSecondaryDns: String = "1.0.0.1",
    val splitTunnelAppOnly: Boolean = false,
    val blockTrackers: Boolean = false,
    val selectedAddonPackage: String = "de.mobileconcepts.cyberghost",
    val customAddonPackages: List<String> = emptyList()
)

data class VpnStatusInfo(
    val isVpnActive: Boolean,
    val transportSummary: String,
    val isCyberGhostInstalled: Boolean,
    val cyberGhostPackageName: String?,
    val isInbuiltVpnRunning: Boolean = false,
    val inbuiltProfileName: String = "",
    val installedAddOnVpns: List<InstalledVpnApp> = emptyList()
)

/**
 * Inspects real Android ConnectivityManager TRANSPORT_VPN capability to power the
 * VPN Kill-Switch guard, controls the inbuilt AeroVpnService privacy tunnel,
 * and discovers/launches optional external Add-On VPN apps via PackageManager / Intents.
 */
object VpnAndCyberGhostHelper {

    private const val PREFS_NAME = "aero_vpn_prefs"
    private const val KEY_MODE = "vpn_mode"
    private const val KEY_PROFILE_ID = "inbuilt_profile_id"
    private const val KEY_CUSTOM_DNS_1 = "custom_dns_1"
    private const val KEY_CUSTOM_DNS_2 = "custom_dns_2"
    private const val KEY_SPLIT_TUNNEL = "split_tunnel_app_only"
    private const val KEY_BLOCK_TRACKERS = "block_trackers"
    private const val KEY_SELECTED_ADDON = "selected_addon_pkg"
    private const val KEY_CUSTOM_ADDONS = "custom_addon_pkgs"

    private val cyberGhostPackages = listOf(
        "de.mobileconcepts.cyberghost",
        "com.cyberghostvpn.android"
    )

    private val curatedAddOnDirectory = listOf(
        "de.mobileconcepts.cyberghost" to "CyberGhost VPN",
        "ch.protonvpn.android" to "Proton VPN",
        "net.mullvad.mullvadvpn" to "Mullvad VPN",
        "com.wireguard.android" to "WireGuard",
        "net.openvpn.openvpn" to "OpenVPN Connect",
        "com.cloudflare.onedotonedotonedotone" to "Cloudflare 1.1.1.1",
        "com.surfshark.vpnclient.android" to "Surfshark VPN"
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun isPackageInstalledApi33(pm: PackageManager, pkg: String): Boolean {
        return try {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            true
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalledCompat(pm: PackageManager, pkg: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPackageInstalledApi33(pm, pkg)
        } else {
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryInstalledVpnServicesCompat(context: Context): List<Pair<String, String>> {
        return try {
            val pm = context.packageManager
            val intent = Intent("android.net.VpnService")
            val resolves = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                pm.queryIntentServices(intent, 0)
            }
            resolves.mapNotNull { info ->
                val pkg = info.serviceInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val appLabel = try {
                    info.serviceInfo.applicationInfo.loadLabel(pm).toString()
                } catch (_: Throwable) {
                    pkg
                }
                pkg to appLabel
            }.distinctBy { it.first }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun loadConfig(context: Context): AeroVpnConfig {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val addonsRaw = sp.getString(KEY_CUSTOM_ADDONS, "[]") ?: "[]"
        val addonsList = mutableListOf<String>()
        try {
            val arr = JSONArray(addonsRaw)
            for (i in 0 until arr.length()) {
                val p = arr.optString(i).trim()
                if (p.isNotBlank()) addonsList.add(p)
            }
        } catch (_: Throwable) {
        }
        return AeroVpnConfig(
            selectedMode = sp.getString(KEY_MODE, "INBUILT") ?: "INBUILT",
            inbuiltProfileId = sp.getString(KEY_PROFILE_ID, "cloudflare") ?: "cloudflare",
            customPrimaryDns = sp.getString(KEY_CUSTOM_DNS_1, "1.1.1.1") ?: "1.1.1.1",
            customSecondaryDns = sp.getString(KEY_CUSTOM_DNS_2, "1.0.0.1") ?: "1.0.0.1",
            splitTunnelAppOnly = sp.getBoolean(KEY_SPLIT_TUNNEL, false),
            blockTrackers = sp.getBoolean(KEY_BLOCK_TRACKERS, false),
            selectedAddonPackage = sp.getString(KEY_SELECTED_ADDON, "de.mobileconcepts.cyberghost")
                ?: "de.mobileconcepts.cyberghost",
            customAddonPackages = addonsList.distinct()
        )
    }

    fun saveConfig(context: Context, config: AeroVpnConfig) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        config.customAddonPackages.distinct().forEach { arr.put(it) }
        sp.edit()
            .putString(KEY_MODE, config.selectedMode)
            .putString(KEY_PROFILE_ID, config.inbuiltProfileId)
            .putString(KEY_CUSTOM_DNS_1, config.customPrimaryDns)
            .putString(KEY_CUSTOM_DNS_2, config.customSecondaryDns)
            .putBoolean(KEY_SPLIT_TUNNEL, config.splitTunnelAppOnly)
            .putBoolean(KEY_BLOCK_TRACKERS, config.blockTrackers)
            .putString(KEY_SELECTED_ADDON, config.selectedAddonPackage)
            .putString(KEY_CUSTOM_ADDONS, arr.toString())
            .apply()
    }

    fun addCustomAddonPackage(context: Context, packageName: String): AeroVpnConfig {
        val clean = packageName.trim()
        val current = loadConfig(context)
        if (clean.isBlank() || clean == context.packageName) return current
        val updated = current.copy(
            selectedMode = "ADDON",
            selectedAddonPackage = clean,
            customAddonPackages = (current.customAddonPackages + clean).distinct()
        )
        saveConfig(context, updated)
        return updated
    }

    fun removeCustomAddonPackage(context: Context, packageName: String): AeroVpnConfig {
        val current = loadConfig(context)
        val nextList = current.customAddonPackages.filterNot { it == packageName }
        val nextSelected = if (current.selectedAddonPackage == packageName) {
            "de.mobileconcepts.cyberghost"
        } else current.selectedAddonPackage
        val updated = current.copy(
            selectedAddonPackage = nextSelected,
            customAddonPackages = nextList
        )
        saveConfig(context, updated)
        return updated
    }

    fun getAvailableAddOnVpns(context: Context): List<InstalledVpnApp> {
        val pm = context.packageManager
        val config = loadConfig(context)
        val discovered = queryInstalledVpnServicesCompat(context).toMap()

        val result = linkedMapOf<String, InstalledVpnApp>()

        // 1. Dynamically discovered installed VpnService apps on the device
        discovered.forEach { (pkg, label) ->
            result[pkg] = InstalledVpnApp(
                packageName = pkg,
                displayName = label,
                isInstalled = true
            )
        }

        // 2. Curated recognized VPN add-on options
        curatedAddOnDirectory.forEach { (pkg, label) ->
            val installed = result.containsKey(pkg) || try {
                isPackageInstalledCompat(pm, pkg)
            } catch (_: Throwable) {
                false
            }
            if (!result.containsKey(pkg)) {
                result[pkg] = InstalledVpnApp(
                    packageName = pkg,
                    displayName = label,
                    isInstalled = installed
                )
            }
        }

        // 3. User-added custom VPN package IDs
        config.customAddonPackages.forEach { pkg ->
            val installed = result[pkg]?.isInstalled == true || try {
                isPackageInstalledCompat(pm, pkg)
            } catch (_: Throwable) {
                false
            }
            val label = result[pkg]?.displayName ?: try {
                val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                pm.getApplicationLabel(ai).toString()
            } catch (_: Throwable) {
                pkg
            }
            result[pkg] = InstalledVpnApp(
                packageName = pkg,
                displayName = label,
                isInstalled = installed,
                isCustomAdded = true
            )
        }

        return result.values.sortedByDescending { it.isInstalled }
    }

    fun prepareInbuiltVpnIntent(context: Context): Intent? {
        return try {
            VpnService.prepare(context)
        } catch (_: Throwable) {
            null
        }
    }

    fun startInbuiltVpn(context: Context, config: AeroVpnConfig = loadConfig(context)): Boolean {
        return try {
            val profile = AeroVpnService.builtInProfiles.find { it.id == config.inbuiltProfileId }
            val dns1 = if (config.inbuiltProfileId == "custom") config.customPrimaryDns else (profile?.primaryDns ?: "1.1.1.1")
            val dns2 = if (config.inbuiltProfileId == "custom") config.customSecondaryDns else (profile?.secondaryDns ?: "1.0.0.1")
            val blockTrackers = if (config.inbuiltProfileId == "custom") config.blockTrackers else (profile?.blocksTrackers ?: config.blockTrackers)

            val intent = AeroVpnService.startIntent(
                context = context,
                profileId = config.inbuiltProfileId,
                primaryDns = dns1,
                secondaryDns = dns2,
                splitTunnelAppOnly = config.splitTunnelAppOnly,
                blockTrackers = blockTrackers
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun stopInbuiltVpn(context: Context): Boolean {
        return try {
            context.startService(AeroVpnService.stopIntent(context))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun inspectStatus(context: Context): VpnStatusInfo {
        val inbuiltState = AeroVpnService.state.value
        var vpnActive = inbuiltState.isRunning
        var summary = if (inbuiltState.isRunning) {
            "Inbuilt ${inbuiltState.activeProfileName} (${inbuiltState.primaryDns})"
        } else {
            "No active VPN tunnel detected"
        }

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val activeNet = cm.activeNetwork
                val activeCaps = activeNet?.let { cm.getNetworkCapabilities(it) }
                if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    vpnActive = true
                    if (!inbuiltState.isRunning) {
                        summary = "Encrypted Add-On VPN Tunnel Active"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val anyVpn = cm.allNetworks.any { net ->
                        cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                    }
                    if (anyVpn) {
                        vpnActive = true
                        if (!inbuiltState.isRunning) {
                            summary = "VPN Interface Active"
                        }
                    } else if (!inbuiltState.isRunning && activeCaps != null) {
                        val underlying = when {
                            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi (Unprotected / Direct)"
                            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular (Unprotected / Direct)"
                            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet (Direct)"
                            else -> "Direct Connection"
                        }
                        summary = underlying
                    }
                }
            }
        } catch (_: Throwable) {
        }

        val installedPkg = try {
            val pm = context.packageManager
            cyberGhostPackages.firstOrNull { pkg -> isPackageInstalledCompat(pm, pkg) }
        } catch (_: Throwable) {
            null
        }

        val addOnList = getAvailableAddOnVpns(context)

        return VpnStatusInfo(
            isVpnActive = vpnActive,
            transportSummary = summary,
            isCyberGhostInstalled = installedPkg != null,
            cyberGhostPackageName = installedPkg,
            isInbuiltVpnRunning = inbuiltState.isRunning,
            inbuiltProfileName = if (inbuiltState.isRunning) inbuiltState.activeProfileName else "",
            installedAddOnVpns = addOnList
        )
    }

    fun launchVpnApp(context: Context, packageName: String): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                val marketIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(marketIntent)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun launchCyberGhostOrStore(context: Context): Boolean {
        val status = inspectStatus(context)
        val targetPkg = status.cyberGhostPackageName ?: "de.mobileconcepts.cyberghost"
        return launchVpnApp(context, targetPkg)
    }

    fun openSystemVpnSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
